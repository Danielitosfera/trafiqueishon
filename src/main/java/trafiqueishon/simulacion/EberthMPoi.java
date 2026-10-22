package trafiqueishon.simulacion;

/** Punto de interés (hospital, mercado…) al que pueden dirigirse los pasajeros de una micro. */
public record EberthMPoi(String nombre, double lon, double lat, EberthMCategoriaPoi categoria) {
    public EberthMPoi {
        if (categoria == null) categoria = EberthMCategoriaPoi.OTRO;
    }

    /** Popularidad 0-100, asignada según la categoría. */
    public int popularidad() {
        return categoria.popularidad();
    }

    /** Personas por hora estimadas, asignadas según la categoría. */
    public int personasPromedio() {
        return categoria.personasPromedio();
    }

    public EberthMPoi(String nombre, double lon, double lat) {
        this(nombre, lon, lat, EberthMCategoriaPoi.OTRO);
    }
}
