package trafiqueishon.simulacion;

public record EstadisticasSimulacion(
        int vehiculosActivos,
        long viajesCompletados,
        double tiempoSimuladoSegundos,
        double velocidadMediaKmH,
        int tramosCongestionados) {
}
