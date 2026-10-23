package trafiqueishon.simulacion;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ejecuta el {@link EberthMSimuladorBuses} en un hilo propio para que miles de
 * vehículos y eventos no congelen la interfaz.
 *
 * <p>Modelo de «actor»: el simulador (y los costos del grafo que modifica)
 * solo se tocan desde el hilo {@code motor-simulacion}. La interfaz envía
 * órdenes con {@link #ejecutar(Consumer)} / {@link #calcular(Function)} y lee el
 * estado con {@link #instantanea()}, una foto inmutable publicada en un campo
 * {@code volatile} tras cada paso. El {@code AnimationTimer} del mapa solo lee
 * esa foto: nunca hay bloqueos entre el render y la simulación.</p>
 */
public final class EberthMMotorSimulacion implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(EberthMMotorSimulacion.class.getName());

    /** Frecuencia del paso de simulación (independiente de los FPS del render). */
    public static final int PASOS_POR_SEGUNDO = 30;

    /** Segundos simulados máximos que avanza un solo tick: a x40 el hilo calcula ~40 ticks por segundo. */
    static final double MAX_SEGUNDOS_SIMULADOS_POR_TICK = 1.0;
    /** Presupuesto de un paso del reloj: si se agota, se omiten ticks (la simulación va más lenta, no se acumula). */
    static final long PRESUPUESTO_PASO_NANOS = 60_000_000L;

    /** Foto inmutable del estado de la simulación. */
    public record Instantanea(List<EberthMPosicionVehiculo> vehiculos, boolean ejecutando,
                              boolean haySimulacion, int horaMinutos, double segundosSimulados,
                              int pasajerosTransportados, double ocupacionPromedio,
                              int vehiculosConIncidencia, long version,
                              List<EberthMParadaEspera> paradas,
                              double esperaPromedioMin, int pasajerosAbandonaron,
                              int pasajerosBajaron, int pasajerosCaminando,
                              double[] caminantes, int zonasSinCobertura,
                              int pasajerosSinCobertura, int transbordos,
                              double[] zonasSinCoberturaXY, String peorZonaSinCobertura,
                              long pasajerosGenerados) {
        public static final Instantanea VACIA =
                new Instantanea(List.of(), false, false, 0, 0, 0, 0, 0, 0, List.of());

        public Instantanea(List<EberthMPosicionVehiculo> vehiculos, boolean ejecutando,
                           boolean haySimulacion, int horaMinutos, double segundosSimulados,
                           int pasajerosTransportados, double ocupacionPromedio,
                           int vehiculosConIncidencia, long version,
                           List<EberthMParadaEspera> paradas) {
            this(vehiculos, ejecutando, haySimulacion, horaMinutos, segundosSimulados,
                    pasajerosTransportados, ocupacionPromedio, vehiculosConIncidencia, version,
                    paradas, 0, 0, 0, 0, new double[0], 0, 0, 0, new double[0], "", 0);
        }
    }

    private final EberthMSimuladorBuses simulador;
    private final ScheduledExecutorService hilo;
    private final ExecutorService hilosAstar;
    private volatile Instantanea instantanea = Instantanea.VACIA;
    private volatile Consumer<Throwable> onError;
    private long ultimoPasoNanos;
    private long version;

    public EberthMMotorSimulacion() {
        this(new EberthMSimuladorBuses(), true);
    }

    /**
     * @param simulador  simulador que pasa a pertenecer al motor
     * @param autoAvance {@code false} desactiva el reloj interno (útil en pruebas,
     *                   que avanzan con {@link #avanzarManual(double)})
     */
    public EberthMMotorSimulacion(EberthMSimuladorBuses simulador, boolean autoAvance) {
        this.simulador = Objects.requireNonNull(simulador);
        this.hilo = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "motor-simulacion");
            t.setDaemon(true);
            return t;
        });
        // Los desvíos A* se calculan fuera del hilo de simulación: el micro espera sin congelar nada.
        this.hilosAstar = autoAvance ? Executors.newFixedThreadPool(
                Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)), r -> {
                    Thread t = new Thread(r, "astar-desvios");
                    t.setDaemon(true);
                    return t;
                }) : null;
        if (hilosAstar != null) simulador.setEjecutorDesvios(hilosAstar);
        if (autoAvance) {
            long periodo = 1_000_000_000L / PASOS_POR_SEGUNDO;
            hilo.scheduleAtFixedRate(this::paso, periodo, periodo, TimeUnit.NANOSECONDS);
        }
    }

    /** Callback (desde el hilo del motor) cuando un paso falla y la simulación se pausa. */
    public void setOnError(Consumer<Throwable> onError) {
        this.onError = onError;
    }

    /** Última foto publicada. Seguro desde cualquier hilo y sin bloqueo. */
    public Instantanea instantanea() {
        return instantanea;
    }

    /** Encola una orden sobre el simulador; se ejecuta en el hilo del motor. */
    public CompletableFuture<Void> ejecutar(Consumer<EberthMSimuladorBuses> orden) {
        return calcular(s -> {
            orden.accept(s);
            return null;
        });
    }

    /** Encola un cálculo sobre el simulador y devuelve su resultado. */
    public <T> CompletableFuture<T> calcular(Function<EberthMSimuladorBuses, T> calculo) {
        CompletableFuture<T> resultado = new CompletableFuture<>();
        try {
            hilo.execute(() -> {
                try {
                    T valor = calculo.apply(simulador);
                    // Tras una orden larga no queremos un salto de tiempo simulado.
                    ultimoPasoNanos = System.nanoTime();
                    publicar();
                    resultado.complete(valor);
                } catch (Throwable t) {
                    publicar();
                    resultado.completeExceptionally(t);
                }
            });
        } catch (RuntimeException rechazada) {
            resultado.completeExceptionally(rechazada);
        }
        return resultado;
    }

    /** Avanza un paso fijo (pruebas o modo sin reloj). */
    public CompletableFuture<Void> avanzarManual(double segundos) {
        return ejecutar(s -> s.avanzar(segundos));
    }

    private void paso() {
        try {
            long ahora = System.nanoTime();
            if (ultimoPasoNanos == 0 || !simulador.estaEjecutando()) {
                ultimoPasoNanos = ahora;
                return;
            }
            double delta = Math.min(1.0, (ahora - ultimoPasoNanos) / 1_000_000_000.0);
            ultimoPasoNanos = ahora;
            // A velocidad alta el paso se parte en ticks de ≤ 1 s simulado (los vehículos no «saltan»
            // tramos). El render solo ve la foto publicada al final de todos los ticks.
            int ticks = (int) Math.clamp(
                    Math.ceil(delta * simulador.getVelocidadSimulacion() / MAX_SEGUNDOS_SIMULADOS_POR_TICK),
                    1, 64);
            double deltaTick = delta / ticks;
            for (int i = 0; i < ticks; i++) {
                simulador.avanzar(deltaTick);
                if (System.nanoTime() - ahora > PRESUPUESTO_PASO_NANOS) break;
            }
            publicar();
        } catch (Throwable t) {
            // Un paso fallido nunca debe matar el hilo programado.
            LOG.log(Level.SEVERE, "Error en el paso de simulación; se pausa", t);
            try {
                simulador.pausar();
                publicar();
            } catch (Throwable ignorado) {
                // Estado irrecuperable: la foto anterior sigue siendo válida.
            }
            Consumer<Throwable> callback = onError;
            if (callback != null) callback.accept(t);
        }
    }

    private void publicar() {
        instantanea = new Instantanea(
                simulador.posiciones(),
                simulador.estaEjecutando(),
                simulador.haySimulacion(),
                simulador.horaActualMinutos(),
                simulador.segundosTranscurridos(),
                simulador.pasajerosTransportados(),
                simulador.ocupacionPromedio(),
                simulador.vehiculosConIncidencia(),
                ++version,
                simulador.paradasEnEspera(),
                simulador.esperaPromedioMinutos(),
                simulador.pasajerosAbandonaron(),
                simulador.pasajerosBajaron(),
                simulador.pasajerosCaminando(),
                simulador.posicionesCaminantes(),
                simulador.zonasSinCobertura(),
                simulador.pasajerosSinCobertura(),
                simulador.transbordos(),
                simulador.zonasSinCoberturaXY(),
                simulador.peorZonaSinCobertura(),
                simulador.pasajerosGenerados());
    }

    @Override
    public void close() {
        hilo.shutdownNow();
        if (hilosAstar != null) hilosAstar.shutdownNow();
    }
}
