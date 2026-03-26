using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace SimuladorIoTComplejo
{
    // ---------------------------------------------------------
    // 1. DEFINICIONES DE DATOS (Records & Enums)
    // ---------------------------------------------------------
    public enum TipoAlerta { Info, Advertencia, Critica }
    
    // 'record' es inmutable, ideal para transmisión de datos
    public record LecturaSensor(Guid IdDispositivo, double Valor, DateTime Timestamp);
    
    public record AlertaSistema(TipoAlerta Nivel, string Mensaje, DateTime Hora);

    // ---------------------------------------------------------
    // 2. INTERFACES Y CONTRATOS
    // ---------------------------------------------------------
    public interface IDispositivoIoT
    {
        Guid Id { get; }
        string Nombre { get; }
        bool EstaActivo { get; }
        Task ConectarAsync();
        Task DesconectarAsync();
    }

    // ---------------------------------------------------------
    // 3. CLASES BASE Y LÓGICA DE NEGOCIO
    // ---------------------------------------------------------
    
    // Excepción personalizada
    public class DispositivoFalloException : Exception
    {
        public DispositivoFalloException(string message) : base(message) { }
    }

    // Clase abstracta base
    public abstract class DispositivoBase : IDispositivoIoT
    {
        public Guid Id { get; } = Guid.NewGuid();
        public string Nombre { get; protected set; }
        public bool EstaActivo { get; private set; }

        // Evento usando EventHandler estándar
        public event EventHandler<string>? OnEstadoCambiado;

        protected DispositivoBase(string nombre)
        {
            Nombre = nombre;
        }

        public async Task ConectarAsync()
        {
            // Simula latencia de red
            await Task.Delay(new Random().Next(200, 800));
            EstaActivo = true;
            OnEstadoCambiado?.Invoke(this, $"[CONECTADO] {Nombre} está en línea.");
        }

        public async Task DesconectarAsync()
        {
            await Task.Delay(200);
            EstaActivo = false;
            OnEstadoCambiado?.Invoke(this, $"[DESCONECTADO] {Nombre} se ha apagado.");
        }

        // Método abstracto que deben implementar los hijos
        public abstract Task<LecturaSensor> GenerarLecturaAsync();
    }

    // ---------------------------------------------------------
    // 4. IMPLEMENTACIONES CONCRETAS
    // ---------------------------------------------------------
    
    public class SensorTemperatura : DispositivoBase
    {
        private readonly Random _rnd = new();

        public SensorTemperatura(string nombre) : base(nombre) { }

        public override async Task<LecturaSensor> GenerarLecturaAsync()
        {
            if (!EstaActivo) throw new DispositivoFalloException($"El sensor {Nombre} está apagado.");

            // Simula procesamiento
            await Task.Delay(_rnd.Next(100, 300));

            // Genera temperatura aleatoria entre 18 y 45 grados
            double temp = 18 + (_rnd.NextDouble() * 27);
            
            // Simula un fallo aleatorio (10% de probabilidad)
            if (_rnd.Next(0, 100) > 90) 
                throw new DispositivoFalloException($"Fallo de hardware en {Nombre}");

            return new LecturaSensor(Id, Math.Round(temp, 2), DateTime.Now);
        }
    }

    public class SensorConsumoEnergetico : DispositivoBase
    {
        private readonly Random _rnd = new();
        public SensorConsumoEnergetico(string nombre) : base(nombre) { }

        public override async Task<LecturaSensor> GenerarLecturaAsync()
        {
            if (!EstaActivo) return new LecturaSensor(Id, 0, DateTime.Now);
            await Task.Delay(100);
            return new LecturaSensor(Id, _rnd.Next(50, 500), DateTime.Now); // Watts
        }
    }

    // ---------------------------------------------------------
    // 5. ORQUESTADOR (EL CEREBRO DEL SISTEMA)
    // ---------------------------------------------------------
    public class CentralHub
    {
        private readonly List<IDispositivoIoT> _dispositivos = new();
        // ConcurrentBag es seguro para hilos (Thread-Safe)
        private readonly ConcurrentBag<LecturaSensor> _historialLecturas = new();
        
        // Delegado para alertas
        public Action<AlertaSistema>? OnAlertaRecibida;

        public void RegistrarDispositivo(IDispositivoIoT dispositivo)
        {
            _dispositivos.Add(dispositivo);
            
            if (dispositivo is DispositivoBase baseDisp)
            {
                baseDisp.OnEstadoCambiado += (sender, msg) => 
                {
                    Console.ForegroundColor = ConsoleColor.Cyan;
                    Console.WriteLine($"HUB NOTIFICACIÓN: {msg}");
                    Console.ResetColor();
                };
            }
        }

        public async Task IniciarSistemaAsync()
        {
            Console.WriteLine("Iniciando secuencia de arranque del Hub...");
            var tareasConexion = _dispositivos.Select(d => d.ConectarAsync());
            await Task.WhenAll(tareasConexion); // Paralelismo real
            Console.WriteLine("Todos los dispositivos están sincronizados.\n");
        }

        public async Task EjecutarCicloMonitoreoAsync(int ciclos)
        {
            for (int i = 0; i < ciclos; i++)
            {
                Console.WriteLine($"--- Ciclo de Lectura {i + 1}/{ciclos} ---");

                // Lista de tareas para ejecutar lecturas en paralelo
                var tareasLectura = _dispositivos
                    .OfType<DispositivoBase>() // Filtrar solo los que pueden leer
                    .Select(async d => 
                    {
                        try
                        {
                            var lectura = await d.GenerarLecturaAsync();
                            _historialLecturas.Add(lectura);
                            Console.WriteLine($"   -> Dato recibido de {d.Nombre}: {lectura.Valor}");

                            // Lógica de negocio reactiva (si temperatura > 40, alerta)
                            if (d is SensorTemperatura && lectura.Valor > 40)
                            {
                                OnAlertaRecibida?.Invoke(new AlertaSistema(
                                    TipoAlerta.Critica, 
                                    $"SOBRECALENTAMIENTO en {d.Nombre}: {lectura.Valor}°C", 
                                    DateTime.Now));
                            }
                        }
                        catch (DispositivoFalloException ex)
                        {
                            OnAlertaRecibida?.Invoke(new AlertaSistema(
                                TipoAlerta.Advertencia, 
                                ex.Message, 
                                DateTime.Now));
                        }
                    });

                await Task.WhenAll(tareasLectura);
                await Task.Delay(1500); // Esperar antes del siguiente ciclo
            }
        }

        public void GenerarReporteFinal()
        {
            Console.WriteLine("\n================ REPORTE FINAL ================");
            
            // Uso de LINQ avanzado para agrupar y calcular estadísticas
            var reporte = _historialLecturas
                .GroupBy(l => l.IdDispositivo)
                .Select(g => new 
                {
                    Id = g.Key,
                    Promedio = g.Average(x => x.Valor),
                    Maximo = g.Max(x => x.Valor),
                    TotalLecturas = g.Count()
                })
                .ToList();

            foreach (var r in reporte)
            {
                var nombre = _dispositivos.FirstOrDefault(d => d.Id == r.Id)?.Nombre ?? "Desconocido";
                Console.WriteLine($"Dispositivo: {nombre} | Prom: {r.Promedio:F2} | Máx: {r.Maximo:F2} | N° Datos: {r.TotalLecturas}");
            }
            Console.WriteLine("===============================================");
        }
    }

    // ---------------------------------------------------------
    // 6. PUNTO DE ENTRADA (MAIN)
    // ---------------------------------------------------------
    class Program
    {
        static async Task Main(string[] args)
        {
            Console.Title = "Simulador IoT Avanzado";
            
            // Inyección de dependencias (Manual)
            var hub = new CentralHub();

            // Suscripción al evento de alertas (Lambda Expression)
            hub.OnAlertaRecibida = (alerta) => 
            {
                var color = alerta.Nivel == TipoAlerta.Critica ? ConsoleColor.Red : ConsoleColor.Yellow;
                Console.ForegroundColor = color;
                Console.WriteLine($"\n[ALERTA {alerta.Nivel.ToString().ToUpper()}] {alerta.Mensaje} ({alerta.Hora:T})\n");
                Console.ResetColor();
            };

            // Creación de dispositivos (Factory pattern simplificado)
            hub.RegistrarDispositivo(new SensorTemperatura("Sensor Sala Servidores"));
            hub.RegistrarDispositivo(new SensorTemperatura("Sensor Planta Baja"));
            hub.RegistrarDispositivo(new SensorConsumoEnergetico("Medidor General"));
            hub.RegistrarDispositivo(new SensorTemperatura("Sensor Exterior (Propenso a fallos)"));

            // Ejecución asíncrona
            await hub.IniciarSistemaAsync();
            
            // Correr 5 ciclos de simulación
            await hub.EjecutarCicloMonitoreoAsync(5);

            // Análisis de datos con LINQ
            hub.GenerarReporteFinal();

            Console.WriteLine("\nSimulación finalizada. Presione una tecla para salir.");
            Console.ReadKey();
        }
    }
}