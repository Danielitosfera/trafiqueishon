package trafiqueishon.geo;

import java.util.List;

/**
 * Representa una via (way) del GeoJSON de OpenStreetMap.
 * Despues del pre-procesado, solo se usan projX/projY (coordenadas 0..1).
 * El campo coords se libera para ahorrar memoria.
 */
public class Via {

    public String id;
    public String highway;
    public String name;
    public int lanes;
    public boolean oneway;
    public int maxspeed;
    public String surface;
    public boolean bridge;
    public String ref;

    // Geometria pre-proyectada (0..1)
    public double[] projX;
    public double[] projY;

    // Bounding box en 0..1
    public double minX, minY, maxX, maxY;

    // Solo durante el parseo. Se libera despues con liberarCoords().
    public transient List<double[]> coords;

    public Via() {}

    /** Libera la lista original de coordenadas para ahorrar memoria. */
    public void liberarCoords() {
        if (coords != null) {
            coords.clear();
            coords = null;
        }
    }

    @Override
    public String toString() {
        return "Via{" + id + ", " + highway + ", " + name + "}";
    }
}