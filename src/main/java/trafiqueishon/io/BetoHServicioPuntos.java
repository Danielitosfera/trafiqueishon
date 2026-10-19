package trafiqueishon.io;

import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.ruta.LuisYPuntoImportante;

import java.util.List;

/** Reglas de datos de los puntos de interés que antes estaban en la ventana principal. */
public final class BetoHServicioPuntos {

    /** Centro de Juliaca, usado mientras no haya un mapa cargado. */
    private static final double[] CENTRO_JULIACA = {-70.13, -15.49};

    private BetoHServicioPuntos() {}

    /** Primer id {@code pN} que no usa ningún punto de la lista. */
    public static String nuevoId(List<LuisYPuntoImportante> puntos) {
        int consecutivo = 1;
        while (true) {
            String id = "p" + consecutivo++;
            if (puntos.stream().noneMatch(p -> p.id().equals(id))) return id;
        }
    }

    /** Centro {@code {lon, lat}} del mapa cargado, o el de Juliaca si {@code grafo} es null. */
    public static double[] centroPorDefecto(PieroGGrafoVial grafo) {
        if (grafo == null) return CENTRO_JULIACA.clone();
        return new double[]{(grafo.getMinLon() + grafo.getMaxLon()) / 2,
                (grafo.getMinLat() + grafo.getMaxLat()) / 2};
    }
}
