package trafiqueishon.geo;

import java.util.AbstractList;
import java.util.RandomAccess;

/**
 * Lista inmutable de pares {lon, lat} respaldada por un único {@code double[]}.
 *
 * <p>Reemplaza a {@code List<double[]>} durante la carga del GeoJSON: un punto
 * ocupa 16 bytes en lugar de ~40 (cabecera de arreglo + referencia), lo que
 * marca la diferencia con los millones de vértices de un mapa como Lima.
 * {@link #get(int)} devuelve un arreglo nuevo de vida corta.</p>
 */
public final class PieroGCoordenadasCompactas extends AbstractList<double[]> implements RandomAccess {

    private final double[] datos;

    /** @param datos lon0, lat0, lon1, lat1, ... (se usa sin copiar) */
    public PieroGCoordenadasCompactas(double[] datos) {
        if (datos.length % 2 != 0) throw new IllegalArgumentException("Pares lon/lat incompletos.");
        this.datos = datos;
    }

    @Override
    public double[] get(int indice) {
        return new double[]{lon(indice), lat(indice)};
    }

    public double lon(int indice) {
        return datos[indice * 2];
    }

    public double lat(int indice) {
        return datos[indice * 2 + 1];
    }

    @Override
    public int size() {
        return datos.length / 2;
    }
}
