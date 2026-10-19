package trafiqueishon.simulacion;

/** Qué hace una micro al llegar al final de su ruta de ida. */
public enum EberthMTipoRetorno {
    /** Solo hace la ida: no se dibuja, exporta ni despacha desde el terminal final. */
    SIN_RETORNO("Sin Retorno"),
    /** La vuelta recorre los paraderos de la ida en orden inverso. */
    INVERSO("Retorno Inverso"),
    /** La vuelta tiene paraderos propios, distintos de la ida. */
    MANUAL("Retorno Manual (Nueva Ruta)");

    private final String etiqueta;

    EberthMTipoRetorno(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    @Override
    public String toString() {
        return etiqueta;
    }

    /** Lee un valor guardado; lo desconocido o ausente se interpreta como {@link #INVERSO}. */
    public static EberthMTipoRetorno desdeTexto(String texto) {
        if (texto == null) return INVERSO;
        try {
            return valueOf(texto);
        } catch (IllegalArgumentException ex) {
            return INVERSO;
        }
    }
}
