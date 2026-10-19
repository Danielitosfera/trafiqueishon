package trafiqueishon.simulacion;

/** Punto de interés (hospital, mercado…) al que pueden dirigirse los pasajeros de una micro. */
public record EberthMPoi(String nombre, double lon, double lat, EberthMCategoriaPoi categoria) {
    public EberthMPoi {
        if (categoria == null) categoria = EberthMCategoriaPoi.OTRO;
    }

    public EberthMPoi(String nombre, double lon, double lat) {
        this(nombre, lon, lat, EberthMCategoriaPoi.OTRO);
    }
}
