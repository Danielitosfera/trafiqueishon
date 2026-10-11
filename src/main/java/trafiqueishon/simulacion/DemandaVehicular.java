package trafiqueishon.simulacion;

public record DemandaVehicular(TipoVehiculo tipo, double vehiculosPorMinuto) {
    public DemandaVehicular {
        if (tipo == null || !Double.isFinite(vehiculosPorMinuto)
                || vehiculosPorMinuto < 0 || vehiculosPorMinuto > 100_000) {
            throw new IllegalArgumentException("Configuracion de demanda invalida.");
        }
    }
}
