package trafiqueishon.simulacion;

import java.util.Optional;

/**
 * Programa las salidas de un servicio de micro: cuándo sale cada unidad, desde
 * qué terminal y con cuántos pasajeros. No crea vehículos ni dibuja nada.
 *
 * <p>Reglas: las dos primeras unidades salen juntas (una de cada terminal si hay
 * más de una); después sale una unidad cada {@code frecuenciaMinutos}, alternando
 * terminal de partida y terminal final. Sin retorno, todas salen del terminal de partida.</p>
 */
public final class EberthMDespachador {

    /** Una salida programada. */
    public record Salida(int numeroUnidad, boolean desdeTerminalFinal, int pasajeros) {}

    private EberthMServicioMicro servicio;
    private final EberthMDemandaPasajeros demanda;
    private int unidadesDespachadas;
    private double proximoDespacho;
    private boolean salidaRetrasada;

    /** Tope de espera por una vía saturada; pasado ese tiempo la unidad sale igualmente. */
    public static final double ESPERA_MAXIMA_SATURACION_SEGUNDOS = 600;

    public EberthMDespachador(EberthMServicioMicro servicio) {
        this(servicio, 1);
    }

    /** @param escalaDemanda multiplicador global de la demanda base del servicio (1 o más) */
    public EberthMDespachador(EberthMServicioMicro servicio, int escalaDemanda) {
        this(servicio, servicio == null ? 0 : servicio.pasajerosEsperando(), escalaDemanda);
    }

    /**
     * @param demandaBase  pasajeros por hora de la ruta (derivados de la población de la ciudad)
     * @param escalaDemanda multiplicador global de la demanda (1 o más)
     */
    public EberthMDespachador(EberthMServicioMicro servicio, long demandaBase, int escalaDemanda) {
        this.servicio = java.util.Objects.requireNonNull(servicio);
        if (escalaDemanda < 1) throw new IllegalArgumentException("La escala debe ser 1 o más.");
        if (demandaBase < 0) throw new IllegalArgumentException("La demanda base no puede ser negativa.");
        int demandaEscalada = (int) Math.min(Integer.MAX_VALUE, demandaBase * escalaDemanda);
        this.demanda = new EberthMDemandaPasajeros(demandaEscalada, servicio.unidadesDisponibles());
    }

    /**
     * Entrega la siguiente salida vencida, o vacío si aún no toca o ya salieron
     * todas las unidades. Se llama en bucle hasta que devuelva vacío.
     *
     * @throws ArithmeticException si la demanda acumulada desborda un entero
     */
    public Optional<Salida> siguienteSalida(double segundosTranscurridos, double multiplicadorDemanda) {
        return siguienteSalida(segundosTranscurridos, multiplicadorDemanda, false);
    }

    /**
     * Igual que {@link #siguienteSalida(double, double)}; el intervalo hasta la siguiente
     * salida es {@code frecuenciaPuntaMinutos} en hora punta y {@code frecuenciaMinutos} fuera de ella.
     */
    public Optional<Salida> siguienteSalida(double segundosTranscurridos, double multiplicadorDemanda,
                                            boolean horaPunta) {
        return siguienteSalida(segundosTranscurridos, multiplicadorDemanda, horaPunta, desdeFinal -> true);
    }

    /**
     * Igual que la anterior, pero la unidad espera en el terminal mientras alguna vía de su
     * ruta esté saturada ({@code rutaLibre} recibe si sale del terminal final), hasta
     * {@link #ESPERA_MAXIMA_SATURACION_SEGUNDOS}. Una salida retrasada reinicia el intervalo
     * desde su salida real, para que las unidades retenidas no salgan todas juntas.
     */
    public Optional<Salida> siguienteSalida(double segundosTranscurridos, double multiplicadorDemanda,
                                            boolean horaPunta,
                                            java.util.function.Predicate<Boolean> rutaLibre) {
        if (terminado() || proximoDespacho > segundosTranscurridos) return Optional.empty();
        boolean saleDelFinal = servicio.tieneRetorno() && unidadesDespachadas % 2 == 1;
        if (segundosTranscurridos - proximoDespacho < ESPERA_MAXIMA_SATURACION_SEGUNDOS
                && !rutaLibre.test(saleDelFinal)) {
            salidaRetrasada = true;
            return Optional.empty();
        }
        if (salidaRetrasada) {
            proximoDespacho = segundosTranscurridos;
            salidaRetrasada = false;
        }
        int abordados = demanda.abordarEnSalida(multiplicadorDemanda);
        Salida salida = new Salida(unidadesDespachadas, saleDelFinal, abordados);
        unidadesDespachadas++;
        boolean primeraConMas = unidadesDespachadas == 1 && servicio.unidadesDisponibles() > 1;
        if (!primeraConMas) {
            proximoDespacho += frecuenciaVigenteMinutos(horaPunta) * 60.0;
        }
        return Optional.of(salida);
    }

    /** Frecuencia de salida vigente: la de hora punta o la normal. */
    public int frecuenciaVigenteMinutos(boolean horaPunta) {
        return horaPunta ? servicio.frecuenciaPuntaMinutos() : servicio.frecuenciaMinutos();
    }

    /** Marca el servicio como terminado (p. ej. tras un error aislado). */
    public void finalizar() {
        unidadesDespachadas = servicio.unidadesDisponibles();
    }

    public boolean terminado() {
        return unidadesDespachadas >= servicio.unidadesDisponibles();
    }

    public int unidadesDespachadas() { return unidadesDespachadas; }

    public double proximoDespacho() { return proximoDespacho; }

    public EberthMServicioMicro servicio() { return servicio; }

    /** Reemplaza la configuración del servicio conservando la demanda acumulada. */
    public void actualizarServicio(EberthMServicioMicro actualizado) {
        this.servicio = java.util.Objects.requireNonNull(actualizado);
    }

    public int minutosHastaProximoDespacho(double segundosTranscurridos) {
        return (int) Math.ceil(Math.max(0, proximoDespacho - segundosTranscurridos) / 60);
    }
}
