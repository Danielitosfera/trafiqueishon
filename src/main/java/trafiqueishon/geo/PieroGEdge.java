package trafiqueishon.geo;

public class PieroGEdge {
    public final int id;
    public final PieroGNodo origen;
    public final PieroGNodo destino;
    public final double longitud;
    public final String highway;
    public final String name;
    public final int maxspeed;
    public final boolean oneway;
    public final int lanes;
    public final String ref;

    public double costoBase;
    public volatile double factorTrafico = 1.0;
    public volatile double factorHoraPunta = 1.0;
    public volatile boolean cerrado;
    /** Micros por hora que admite el tramo; se hereda de {@link PieroGVia#flujoMaximo}. */
    public int flujoMaximo = PieroGVia.FLUJO_MAXIMO_POR_DEFECTO;

    public PieroGEdge(int id, PieroGNodo origen, PieroGNodo destino, double longitud,
                String highway, String name, int maxspeed, boolean oneway,
                int lanes, String ref) {
        this.id = id;
        this.origen = origen;
        this.destino = destino;
        this.longitud = longitud;
        this.highway = highway;
        this.name = name;
        this.maxspeed = maxspeed;
        this.oneway = oneway;
        this.lanes = lanes;
        this.ref = ref;
        recalcularCosto();
    }

    public static double velocidadPorTipo(String highway) {
        if (highway == null) return 30;
        return switch (highway) {
            case "motorway" -> 100;
            case "motorway_link" -> 60;
            case "trunk", "trunk_link" -> 80;
            case "primary", "primary_link" -> 60;
            case "secondary", "secondary_link" -> 50;
            case "tertiary", "tertiary_link" -> 40;
            case "residential" -> 25;
            case "unclassified" -> 40;
            case "service" -> 20;
            case "living_street" -> 20;
            case "pedestrian" -> 10;
            default -> 30;
        };
    }

    public void recalcularCosto() {
        double vel = (maxspeed > 0) ? maxspeed : velocidadPorTipo(highway);
        if (vel <= 0) vel = 30;
        double mps = vel / 3.6;
        costoBase = longitud / mps;
    }

    public double costoActual() {
        return cerrado ? Double.POSITIVE_INFINITY
                : costoBase * factorTrafico * factorHoraPunta;
    }

    public PieroGNodo origen() {
        return origen;
    }

    public PieroGNodo destino() {
        return destino;
    }

    public PieroGNodo getOrigen() {
        return origen;
    }

    public PieroGNodo getDestino() {
        return destino;
    }

    public double longitudMetros() {
        return longitud;
    }

    public double velocidadLibreMps() {
        double velocidad = maxspeed > 0 ? maxspeed : velocidadPorTipo(highway);
        return Math.max(1.0, velocidad / 3.6);
    }

    @Override
    public String toString() {
        return "Edge#" + id + "[" + origen.id + "->" + destino.id
                + " " + highway + " " + String.format("%.0fs", costoBase) + "]";
    }
}