package trafiqueishon.geo;

public class Edge {
    public final int id;
    public final Nodo origen;
    public final Nodo destino;

    /** Longitud geodesica aproximada, en metros. */
    public final double longitud;

    public final String highway;
    public final String name;
    public final int maxspeed;
    public final boolean oneway;
    public final int lanes;
    public final String ref;

    /** Costo base = longitud / velocidadMaxima. Segundos aproximados. */
    public double costoBase;

    /** Factor de congestion: 1.0 = libre, 2.0 = congestionado. Base para futuro simulador. */
    public volatile double factorTrafico = 1.0;

    public Edge(int id, Nodo origen, Nodo destino, double longitud,
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

    /** Velocidad por defecto segun tipo de via (km/h). */
    public static double velocidadPorTipo(String highway) {
        if (highway == null) return 30;
        switch (highway) {
            case "motorway":        return 100;
            case "motorway_link":   return 60;
            case "trunk":
            case "trunk_link":     return 80;
            case "primary":
            case "primary_link":   return 60;
            case "secondary":
            case "secondary_link": return 50;
            case "tertiary":
            case "tertiary_link":  return 40;
            case "residential":    return 30;
            case "unclassified":   return 40;
            case "service":        return 20;
            case "living_street":  return 15;
            default:               return 30;
        }
    }

    public void recalcularCosto() {
        double vel = (maxspeed > 0) ? maxspeed : velocidadPorTipo(highway);
        if (vel <= 0) vel = 30;
        double mps = vel / 3.6;
        costoBase = longitud / mps;
    }

    /** Costo efectivo usado por Dijkstra/A*: aplica congestion si existe. */
    public double costoActual() {
        return costoBase * factorTrafico;
    }

    /** Metros reales aproximados de este tramo. */
    public double longitudMetros() {
        return longitud;
    }

    public double velocidadLibreMps() {
        double velocidad = maxspeed > 0 ? maxspeed : velocidadPorTipo(highway);
        return Math.max(1.0, velocidad / 3.6);
    }

    @Override public String toString() {
        return "Edge#" + id + "[" + origen.id + "->" + destino.id
                + " " + highway + " " + String.format("%.0fs", costoBase) + "]";
    }
}
