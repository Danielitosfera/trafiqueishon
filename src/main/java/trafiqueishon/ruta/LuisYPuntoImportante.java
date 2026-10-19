package trafiqueishon.ruta;

public record LuisYPuntoImportante(
        String id,
        String nombre,
        double lon,
        double lat,
        TipoPunto tipo) {

    public enum TipoPunto {
        ORIGEN, DESTINO, PARADA, REFERENCIA, PEaje, CONTROL, TERMINAL, POI,
        // Categorías de puntos de interés (puntos.json de cada ciudad).
        EDUCACION, INSTITUCION, MERCADO, TRANSPORTE, SALUD, PARQUE, TURISMO,
        DEPORTE, PLAZA, COMERCIO;

        /** Interpreta el texto del JSON/Excel; los valores desconocidos pasan a PARADA. */
        public static TipoPunto desdeTexto(String texto) {
            if (texto == null || texto.isBlank()) return PARADA;
            String normal = java.text.Normalizer.normalize(texto.trim(), java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "").toUpperCase(java.util.Locale.ROOT);
            for (TipoPunto tipo : values()) {
                if (tipo.name().equalsIgnoreCase(normal)) return tipo;
            }
            return PARADA;
        }
    }

    public LuisYPuntoImportante {
        if (id == null || id.isBlank() || nombre == null || nombre.isBlank()
                || !Double.isFinite(lon) || !Double.isFinite(lat)
                || lon < -180 || lon > 180 || lat < -90 || lat > 90) {
            throw new IllegalArgumentException("Punto importante invalido.");
        }
        tipo = tipo == null ? TipoPunto.PARADA : tipo;
    }

    public LuisYPuntoImportante(String id, String nombre, double lon, double lat) {
        this(id, nombre, lon, lat, TipoPunto.PARADA);
    }
}