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

    public EberthMDespachador(EberthMServicioMicro servicio) {
        this(servicio, 1);
    }

    /** @param escalaDemanda multiplicador global de la demanda base del servicio (1 o más) */
    public EberthMDespachador(EberthMServicioMicro servicio, int escalaDemanda) {
        this.servicio = java.util.Objects.requireNonNull(servicio);
        if (escalaDemanda < 1) throw new IllegalArgumentException("La escala debe ser 1 o más.");
        int demandaBase = (int) Math.min(Integer.MAX_VALUE,
                (long) servicio.pasajerosEsperando() * escalaDemanda);
        this.demanda = new EberthMDemandaPasajeros(demandaBase, servicio.unidadesDisponibles());
    }

    /**
     * Entrega la siguiente salida vencida, o vacío si aún no toca o ya salieron
     * todas las unidades. Se llama en bucle hasta que devuelva vacío.
     *
     * @throws ArithmeticException si la demanda acumulada desborda un entero
     */
    public Optional<Salida> siguienteSalida(double segundosTranscurridos, int minutoDelDia) {
        if (terminado() || proximoDespacho > segundosTranscurridos) return Optional.empty();
        int abordados = demanda.abordarEnSalida(minutoDelDia);
        boolean saleDelFinal = servicio.tieneRetorno() && unidadesDespachadas % 2 == 1;
        Salida salida = new Salida(unidadesDespachadas, saleDelFinal, abordados);
        unidadesDespachadas++;
        boolean primeraConMas = unidadesDespachadas == 1 && servicio.unidadesDisponibles() > 1;
        if (!primeraConMas) proximoDespacho += servicio.frecuenciaMinutos() * 60.0;
        return Optional.of(salida);
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
