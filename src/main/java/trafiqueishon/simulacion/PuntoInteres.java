package trafiqueishon.simulacion;

public record PuntoInteres(String id, String nombre, double lon, double lat, int peso) {
    public PuntoInteres {
        if (id == null || id.isBlank() || nombre == null || nombre.isBlank()
                || !Double.isFinite(lon) || !Double.isFinite(lat)
                || lon < -180 || lon > 180 || lat < -90 || lat > 90
                || peso < 1 || peso > 1_000_000) {
            throw new IllegalArgumentException("Punto de interes invalido.");
        }
    }

    public PuntoInteres(String id, String nombre, double lon, double lat) {
        this(id, nombre, lon, lat, 1);
    }
}
