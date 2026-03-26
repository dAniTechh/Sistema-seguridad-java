import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

// =========================================================
// 1. SEGURIDAD (PBKDF2 + Salting) - NIVEL BANCARIO
// =========================================================
class CryptoUtils {
    private static final int ITERATIONS = 600_000; // ¡Fuerza bruta imposible!
    private static final int KEY_LENGTH = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    public static byte[] generateSalt() { byte[] s = new byte[16]; RANDOM.nextBytes(s); return s; }
    
    public static String hashPassword(char[] p, byte[] s) {
        PBEKeySpec spec = new PBEKeySpec(p, s, ITERATIONS, KEY_LENGTH);
        try { return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded()); } 
        catch (Exception e) { throw new RuntimeException(e); } 
        finally { spec.clearPassword(); }
    }
}

class UserSecurityProfile {
    final String username, passwordHash, saltBase64;
    int failedAttempts = 0; Instant lockedUntil = null;
    public UserSecurityProfile(String u, String h, String s) { username=u; passwordHash=h; saltBase64=s; }
    public boolean isLocked() { if(lockedUntil!=null && Instant.now().isAfter(lockedUntil)) lockedUntil=null; return lockedUntil!=null; }
    public void fail() { if(++failedAttempts>=3) lockedUntil=Instant.now().plusSeconds(30); }
    public void reset() { failedAttempts=0; lockedUntil=null; }
}

class SecurityKernel {
    static final SecurityKernel INSTANCE = new SecurityKernel();
    final ConcurrentHashMap<String, UserSecurityProfile> users = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, String> sessions = new ConcurrentHashMap<>();
    private SecurityKernel() {}
    public static SecurityKernel getInstance() { return INSTANCE; }

    public void registrar(String u, String p) {
        byte[] s = CryptoUtils.generateSalt();
        users.put(u, new UserSecurityProfile(u, CryptoUtils.hashPassword(p.toCharArray(), s), Base64.getEncoder().encodeToString(s)));
    }

    public String login(String u, String p) throws Exception {
        UserSecurityProfile profile = users.get(u);
        if (profile == null) { Thread.sleep(500); throw new Exception("Credenciales invalidas"); } 
        if (profile.isLocked()) throw new Exception("CUENTA BLOQUEADA TEMPORALMENTE");
        
        String hash = CryptoUtils.hashPassword(p.toCharArray(), Base64.getDecoder().decode(profile.saltBase64));
        if (!hash.equals(profile.passwordHash)) { profile.fail(); throw new Exception("Credenciales invalidas"); }
        
        profile.reset();
        String token = UUID.randomUUID().toString();
        sessions.put(token, u);
        return token;
    }
    
    public String validar(String t) throws Exception {
        if(!sessions.containsKey(t)) throw new Exception("Token invalido");
        return sessions.get(t);
    }
}

// =========================================================
// 2. MOTOR DE TRADING (PriorityQueue + Match) - NIVEL PRO
// =========================================================
enum Tipo { BID, ASK }
record Orden(long id, String u, Tipo t, double p, int c, long time) implements Comparable<Orden> {
    public int compareTo(Orden o) { 
        int priceCmp = t==Tipo.ASK ? Double.compare(p,o.p) : Double.compare(o.p,p); 
        return priceCmp != 0 ? priceCmp : Long.compare(time, o.time);
    }
}

class Mercado {
    final PriorityBlockingQueue<Orden> bids = new PriorityBlockingQueue<>();
    final PriorityBlockingQueue<Orden> asks = new PriorityBlockingQueue<>();
    final ConcurrentHashMap<String, Double> saldos = new ConcurrentHashMap<>();
    
    public Mercado() { 
        saldos.put("dani", 10000.0); 
        saldos.put("bot", 50000.0); 
    }
    
    public double getSaldo(String u) { return saldos.getOrDefault(u, 0.0); }

    public String colocarOrden(String token, Tipo tipo, double precio, int cant) {
        try {
            String user = SecurityKernel.getInstance().validar(token);
            if (precio <= 0 || cant <= 0) return "ERROR: Datos invalidos";

            if (tipo == Tipo.BID) {
                double coste = precio * cant;
                if (getSaldo(user) < coste) return "ERROR: Fondos insuficientes";
            }
            
            Orden orden = new Orden(System.nanoTime(), user, tipo, precio, cant, System.currentTimeMillis());
            // Lógica de Matching
            if (tipo == Tipo.BID) match(orden, asks, bids); else match(orden, bids, asks);
            
            return "OK: Orden procesada (" + tipo + " " + cant + "u @ " + precio + ")";
        } catch (Exception e) { return "ERROR: " + e.getMessage(); }
    }

    private synchronized void match(Orden in, PriorityBlockingQueue<Orden> matchWith, PriorityBlockingQueue<Orden> book) {
        System.out.println(">>> PROCESANDO: " + in);
        while (in.c() > 0) {
            Orden best = matchWith.peek();
            boolean hayMatch = best != null && (in.t()==Tipo.BID ? in.p()>=best.p() : in.p()<=best.p());

            if (!hayMatch) { book.put(in); return; }

            matchWith.poll(); 
            int trade = Math.min(in.c(), best.c());
            double total = best.p() * trade;
            
            String buyer = in.t()==Tipo.BID ? in.u() : best.u();
            String seller = in.t()==Tipo.BID ? best.u() : in.u();
            
            saldos.put(buyer, saldos.getOrDefault(buyer, 0.0) - total); 
            saldos.put(seller, saldos.getOrDefault(seller, 0.0) + total);

            System.out.println("💰 MATCH: " + buyer + " pagó $" + total + " a " + seller);

            if (best.c() > trade) matchWith.put(new Orden(best.id(), best.u(), best.t(), best.p(), best.c()-trade, best.time()));
            in = new Orden(in.id(), in.u(), in.t(), in.p(), in.c()-trade, in.time());
        }
    }
}

// =========================================================
// 3. LA API WEB (El puente con React)
// =========================================================
public class App {
    static Mercado mercado = new Mercado();
    static SecurityKernel sec = SecurityKernel.getInstance();

    public static void main(String[] args) throws IOException {
        sec.registrar("dani", "1234"); // <--- TU USUARIO Y CONTRASEÑA
        sec.registrar("bot", "root");

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        
        server.createContext("/login", (ex) -> {
            String resp;
            try {
                Map<String,String> p = queryToMap(ex.getRequestURI().getQuery());
                String t = sec.login(p.get("user"), p.get("pass"));
                resp = "LOGIN_OK: " + t;
            } catch (Exception e) { resp = "LOGIN_ERROR: " + e.getMessage(); }
            send(ex, resp);
        });

        server.createContext("/balance", (ex) -> {
            String resp;
            try {
                Map<String,String> p = queryToMap(ex.getRequestURI().getQuery());
                String u = sec.validar(p.get("token"));
                resp = "SALDO: $" + mercado.getSaldo(u);
            } catch (Exception e) { resp = "ERROR: " + e.getMessage(); }
            send(ex, resp);
        });

        server.createContext("/buy", (ex) -> {
            try {
                Map<String,String> p = queryToMap(ex.getRequestURI().getQuery());
                String res = mercado.colocarOrden(p.get("token"), Tipo.BID, 
                    Double.parseDouble(p.get("price")), Integer.parseInt(p.get("cant")));
                send(ex, res);
            } catch (Exception e) { send(ex, "ERROR: " + e.getMessage()); }
        });

        server.setExecutor(null);
        server.start();
        System.out.println(">>> 🔥 MOTOR PRO ONLINE (Listo para React) 🔥");
    }

    static void send(HttpExchange ex, String resp) throws IOException {
        ex.sendResponseHeaders(200, resp.length());
        OutputStream os = ex.getResponseBody();
        os.write(resp.getBytes());
        os.close();
    }
    
    static Map<String, String> queryToMap(String query) {
        Map<String, String> res = new HashMap<>();
        if(query == null) return res;
        for (String p : query.split("&")) {
            String[] pair = p.split("=");
            if (pair.length > 1) res.put(pair[0], pair[1]);
        }
        return res;
    }
}