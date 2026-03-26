import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// ==========================================
// 1. UTILIDADES CRIPTOGRÁFICAS AVANZADAS (PBKDF2)
// ==========================================
class AdvancedCrypto {
    // Configuraciones estándar de la industria (OWASP 2025 recommendations)
    private static final int ITERATIONS = 600_000; // Muy alto para hacer lento el ataque
    private static final int KEY_LENGTH = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    public static byte[] generateSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return salt;
    }

    public static String hashPassword(char[] password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH);
        Arrays.fill(password, '0'); // CRÍTICO: Borrar la contraseña de la RAM inmediatamente

        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] hash = skf.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Error fatal en criptografía", e);
        } finally {
            spec.clearPassword(); // Limpieza extra
        }
    }
}

// ==========================================
// 2. GESTIÓN DE IDENTIDAD Y ESTADO
// ==========================================

// Clase mutable para gestionar el estado de seguridad del usuario
class UserSecurityProfile {
    private final String username;
    private final String passwordHash;
    private final String saltBase64;
    
    // Protección contra fuerza bruta
    private int failedLoginAttempts = 0;
    private Instant lockedUntil = null;

    public UserSecurityProfile(String username, String passwordHash, String saltBase64) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.saltBase64 = saltBase64;
    }

    public boolean isLocked() {
        if (lockedUntil != null && Instant.now().isBefore(lockedUntil)) {
            return true;
        }
        if (lockedUntil != null && Instant.now().isAfter(lockedUntil)) {
            // Desbloqueo automático tras pasar el tiempo
            lockedUntil = null;
            failedLoginAttempts = 0;
            System.out.println("[INFO] Cuenta desbloqueada automáticamente: " + username);
        }
        return false;
    }

    public void registerFailedAttempt() {
        failedLoginAttempts++;
        if (failedLoginAttempts >= 3) {
            lockedUntil = Instant.now().plusSeconds(30); // Bloqueo de 30 segundos para la demo
            System.out.println("[ALERTA] !!! CUENTA BLOQUEADA POR FUERZA BRUTA: " + username + " !!!");
        }
    }

    public void resetAttempts() {
        failedLoginAttempts = 0;
        lockedUntil = null;
    }

    public String getPasswordHash() { return passwordHash; }
    public String getSaltBase64() { return saltBase64; }
}

class SessionInfo {
    final String username;
    final Instant expiresAt;

    SessionInfo(String username) {
        this.username = username;
        this.expiresAt = Instant.now().plusSeconds(60); // Sesiones cortas (1 min)
    }

    boolean isValid() {
        return Instant.now().isBefore(expiresAt);
    }
}

// ==========================================
// 3. KERNEL DE SEGURIDAD (SINGLETON)
// ==========================================
class SecurityKernel {
    private static final SecurityKernel INSTANCE = new SecurityKernel();
    private final ConcurrentHashMap<String, UserSecurityProfile> users = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionInfo> activeSessions = new ConcurrentHashMap<>();

    private SecurityKernel() {}
    public static SecurityKernel getInstance() { return INSTANCE; }

    // Registro seguro
    public void register(String username, String password) {
        byte[] salt = AdvancedCrypto.generateSalt();
        String hash = AdvancedCrypto.hashPassword(password.toCharArray(), salt);
        String saltStr = Base64.getEncoder().encodeToString(salt);
        
        users.put(username, new UserSecurityProfile(username, hash, saltStr));
        System.out.println(">> Usuario registrado: " + username + " (Protegido con PBKDF2-600k)");
    }

    // Login con gestión de errores y bloqueos
    public String login(String username, String password) throws Exception {
        UserSecurityProfile user = users.get(username);
        if (user == null) {
            // Timing Attack Protection: Esperar un poco aunque el usuario no exista
            // para que el hacker no sepa si falló el usuario o la contraseña.
            Thread.sleep(500); 
            throw new Exception("Credenciales inválidas");
        }

        if (user.isLocked()) {
            throw new Exception("CUENTA BLOQUEADA TEMPORALMENTE. Intente más tarde.");
        }

        byte[] salt = Base64.getDecoder().decode(user.getSaltBase64());
        String attemptHash = AdvancedCrypto.hashPassword(password.toCharArray(), salt);

        if (!attemptHash.equals(user.getPasswordHash())) {
            user.registerFailedAttempt();
            throw new Exception("Credenciales inválidas");
        }

        // Éxito
        user.resetAttempts();
        String token = UUID.randomUUID().toString();
        activeSessions.put(token, new SessionInfo(username));
        return token;
    }

    public void verifyToken(String token) throws Exception {
        SessionInfo session = activeSessions.get(token);
        if (session == null || !session.isValid()) {
            activeSessions.remove(token); // Limpiar basura
            throw new Exception("Token inválido o expirado. Inicie sesión de nuevo.");
        }
    }
}

// ==========================================
// 4. SIMULACIÓN DE ATAQUE
// ==========================================
public class BankGradeSecurity {
    public static void main(String[] args) {
        SecurityKernel sec = SecurityKernel.getInstance();

        System.out.println("--- 1. REGISTRO DE USUARIO ---");
        sec.register("admin", "SuperSecreto123");

        System.out.println("\n--- 2. SIMULACIÓN DE ATAQUE DE FUERZA BRUTA ---");
        String[] intentos = {"123456", "password", "admin123", "qwerty", "SuperSecreto123"};

        for (String pass : intentos) {
            System.out.print("Intentando login con: '" + pass + "' -> ");
            try {
                long start = System.currentTimeMillis();
                String token = sec.login("admin", pass);
                long end = System.currentTimeMillis();
                
                System.out.println("ACCESO CONCEDIDO (Token: " + token.substring(0,8) + "...)");
                System.out.println("   (Nota el retraso del cálculo PBKDF2: " + (end - start) + "ms)");
                
                // Prueba de token
                sec.verifyToken(token);
                System.out.println("   Token verificado correctamente.");

            } catch (Exception e) {
                System.out.println("FALLO: " + e.getMessage());
            }
            System.out.println("------------------------------------------------");
        }
    }
}