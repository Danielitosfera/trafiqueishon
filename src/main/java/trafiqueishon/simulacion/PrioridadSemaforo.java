package trafiqueishon.simulacion;

/** Funcion de priorizacion de intersecciones; coeficientes iniciales configurables. */
public final class PrioridadSemaforo {
    public static final double PESO_GRADO = 1.5;
    public static final double PESO_JERARQUIA = 4.0;
    public static final double PESO_VOLUMEN = 2.0;
    public static final double UMBRAL = 14.5;

    private PrioridadSemaforo() {}

    public static double puntaje(int grado, double jerarquia, double volumenEstimado) {
        if (grado < 0 || !Double.isFinite(jerarquia) || jerarquia < 0
                || !Double.isFinite(volumenEstimado) || volumenEstimado < 0) {
            throw new IllegalArgumentException("Parametros de prioridad de semaforo invalidos.");
        }
        if (grado < 3) return 0;
        return PESO_GRADO * grado + PESO_JERARQUIA * jerarquia
                + PESO_VOLUMEN * volumenEstimado;
    }

    public static boolean superaUmbral(double puntaje) {
        return Double.isFinite(puntaje) && puntaje >= UMBRAL;
    }
}
