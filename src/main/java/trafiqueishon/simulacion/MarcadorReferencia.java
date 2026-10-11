package trafiqueishon.simulacion;

public record MarcadorReferencia(String nombre, double lon, double lat) {
    public MarcadorReferencia {
        if (nombre == null || nombre.isBlank() || !Double.isFinite(lon)
                || !Double.isFinite(lat)) {
            throw new IllegalArgumentException("Marcador de referencia invalido.");
        }
    }
}
