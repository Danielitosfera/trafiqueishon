package trafiqueishon.simulacion;

/**
 * Genera la demanda de pasajeros de un servicio de micro y decide cuántos
 * abordan en cada salida. El multiplicador horario lo fija
 * {@link EberthMPerfilHoraPunta#multiplicadorDemanda}, el mismo que usan las paradas.
 */
public final class EberthMDemandaPasajeros {

    /** Máximo de pasajeros que abordan por salida. */
    public static final int ABORDAJE_MAXIMO = 18;

    private final int demandaPorSalida;
    private int pendientes;

    /**
     * @param pasajerosEsperando demanda base total del servicio
     * @param unidades           unidades del servicio entre las que se reparte
     */
    public EberthMDemandaPasajeros(int pasajerosEsperando, int unidades) {
        if (pasajerosEsperando < 0 || unidades < 1) {
            throw new IllegalArgumentException("Demanda o cantidad de unidades inválida.");
        }
        this.demandaPorSalida = (int) Math.ceil((double) pasajerosEsperando / unidades);
    }

    /**
     * Suma la demanda nueva (demanda por salida × multiplicador horario) y devuelve
     * cuántos pasajeros abordan en esta salida.
     *
     * @param multiplicadorDemanda multiplicador de {@link EberthMPerfilHoraPunta#multiplicadorDemanda}
     * @throws ArithmeticException si los pendientes desbordan un entero
     */
    public int abordarEnSalida(double multiplicadorDemanda) {
        int demandaNueva = (int) Math.round(demandaPorSalida * multiplicadorDemanda);
        pendientes = Math.addExact(pendientes, demandaNueva);
        int abordados = Math.min(ABORDAJE_MAXIMO, pendientes);
        pendientes -= abordados;
        return abordados;
    }

    public int demandaPorSalida() { return demandaPorSalida; }

    public int pendientes() { return pendientes; }
}
