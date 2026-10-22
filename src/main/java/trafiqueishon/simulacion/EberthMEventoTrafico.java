package trafiqueishon.simulacion;

/**
 * @param radioMetros radio de acción de los eventos zonales (lluvia); 0 usa
 *                    {@link EberthMTipoEvento#RADIO_LLUVIA_METROS}
 * @param rumbo       hacia dónde guía el policía de tránsito, en radianes sobre el plano del mapa
 *                    (x a la derecha, y hacia abajo); {@code NaN} si el evento no tiene dirección
 */
public record EberthMEventoTrafico(String nombre, EberthMTipoEvento tipo, double lon, double lat,
                                   double radioMetros, double rumbo) {
    public EberthMEventoTrafico {
        if (nombre == null || nombre.isBlank() || tipo == null
                || !Double.isFinite(lon) || !Double.isFinite(lat)
                || lon < -180 || lon > 180 || lat < -90 || lat > 90
                || !Double.isFinite(radioMetros) || radioMetros < 0) {
            throw new IllegalArgumentException("Evento de tráfico inválido.");
        }
    }

    public EberthMEventoTrafico(String nombre, EberthMTipoEvento tipo, double lon, double lat,
                                double radioMetros) {
        this(nombre, tipo, lon, lat, radioMetros, Double.NaN);
    }

    public EberthMEventoTrafico(String nombre, EberthMTipoEvento tipo, double lon, double lat) {
        this(nombre, tipo, lon, lat, 0);
    }

    /** Radio efectivo en metros de una lluvia. */
    public double radioEfectivoMetros() {
        return radioMetros > 0 ? radioMetros : EberthMTipoEvento.RADIO_LLUVIA_METROS;
    }
}
