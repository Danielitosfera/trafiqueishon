package trafiqueishon.simulacion;

/** Ecuaciones del Intelligent Driver Model (IDM), en unidades SI. */
public final class ModeloIDM {
    private static final double EXPONENTE = 4.0;

    private ModeloIDM() {}

    public static double aceleracion(double velocidad, double velocidadLider,
                                     double espacio, double velocidadDeseada,
                                     double tiempoReaccion, double distanciaMinima,
                                     double aceleracionMaxima, double frenadoComodo) {
        if (!Double.isFinite(velocidad) || !Double.isFinite(velocidadLider)
                || Double.isNaN(espacio) || espacio < 0
                || !Double.isFinite(velocidadDeseada) || velocidadDeseada <= 0
                || tiempoReaccion <= 0 || distanciaMinima < 0
                || aceleracionMaxima <= 0 || frenadoComodo <= 0) {
            throw new IllegalArgumentException("Estado o parametros IDM invalidos.");
        }
        if (espacio == Double.POSITIVE_INFINITY) {
            return aceleracionMaxima
                    * (1 - Math.pow(velocidad / velocidadDeseada, EXPONENTE));
        }
        double espacioDeseado = distanciaMinima + Math.max(0,
                velocidad * tiempoReaccion
                        + velocidad * (velocidad - velocidadLider)
                        / (2 * Math.sqrt(aceleracionMaxima * frenadoComodo)));
        return aceleracionMaxima * (1
                - Math.pow(velocidad / velocidadDeseada, EXPONENTE)
                - Math.pow(espacioDeseado / Math.max(0.01, espacio), 2));
    }
}
