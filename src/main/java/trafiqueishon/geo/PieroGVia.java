package trafiqueishon.geo;

import java.util.List;

public class PieroGVia {
    public String id;
    public String highway;
    public String name;
    public int lanes;
    public boolean oneway;
    public boolean reverseOneway;
    public int maxspeed;
    public String surface;
    public boolean bridge;
    public String ref;
    /** Micros por hora que admite la vía (separación mínima de 60 s entre unidades, criterio JICA). */
    public static final int FLUJO_MAXIMO_POR_DEFECTO = 60;
    public int flujoMaximo = FLUJO_MAXIMO_POR_DEFECTO;

    public float[] projX;
    public float[] projY;

    public double minX, minY, maxX, maxY;

    public transient List<double[]> coords;

    public PieroGVia() {}

    /**
     * Suelta las coordenadas geográficas (ya proyectadas). No se usa
     * {@code clear()}: la lista puede ser inmutable ({@link PieroGCoordenadasCompactas},
     * {@code List.of}) o compartida.
     */
    public void liberarCoords() {
        coords = null;
    }

    @Override
    public String toString() {
        return "Via{" + id + ", " + highway + ", " + name + "}";
    }
}