package trafiqueishon.geo;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.*;
import java.util.function.Predicate;

/**
 * Grafo vial construido a partir de una lista de Via.
 *  - Nodos: puntos unicos tras "snap" a una grilla.
 *  - Aristas: segmentos entre nodos consecutivos.
 *  - STRtree: indexado espacial de nodos para busquedas O(log n).
 *
 * IMPORTANTE: llamar a construir(...) ANTES de liberar coords de las Via.
 */
public class GrafoVial {

    /** Precision del snap. 1e-6 grados ~ 0.1 m. Ajusta si hay demasiados nodos. */
    private static final double SNAP = 1e-6;
    private static final Set<String> TIPOS_VEHICULARES = Set.of(
            "motorway", "motorway_link", "trunk", "trunk_link",
            "primary", "primary_link", "secondary", "secondary_link",
            "tertiary", "tertiary_link", "unclassified", "residential",
            "living_street", "service", "road");

    private final List<Nodo> nodos = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private final Map<SnapKey, Integer> snapIndex = new HashMap<>();
    private STRtree strtree = new STRtree();

    private double minLon, minLat, maxLon, maxLat;
    private double velocidadMaximaMps = 1.0;

    public List<Nodo> getNodos() { return nodos; }
    public List<Edge> getEdges() { return edges; }
    public int numNodos() { return nodos.size(); }
    public int numEdges() { return edges.size(); }

    /** Cuantos nodos caben por celda del snap (para estadisticas). */
    public String resumen() {
        return "GrafoVial[nodos=" + nodos.size() + ", edges=" + edges.size() + "]";
    }

    /**
     * Construye el grafo desde las vias.
     * Debe llamarse ANTES de liberar coords.
     */
    public void construir(List<Via> vias, Proyector proyector) {
        nodos.clear();
        edges.clear();
        snapIndex.clear();
        velocidadMaximaMps = 1.0;
        minLon = proyector.getMinLon();
        minLat = proyector.getMinLat();
        maxLon = proyector.getMaxLon();
        maxLat = proyector.getMaxLat();

        int edgeId = 0;
        for (Via v : vias) {
            if (v.coords == null || v.coords.size() < 2) continue;
            if (!esViaVehicular(v.highway)) continue;

            Nodo anterior = null;
            for (int i = 0; i < v.coords.size(); i++) {
                int indice = v.reverseOneway ? v.coords.size() - i - 1 : i;
                double[] c = v.coords.get(indice);
                Nodo actual = nodoEnOLoCrear(c[0], c[1]);
                if (anterior != null && anterior.id != actual.id) {
                    Edge e = crearEdge(edgeId++, anterior, actual, v);
                    anterior.salientes.add(e);
                    actual.entrantes.add(e);
                    edges.add(e);

                    // Si no es oneway, edge inverso
                    if (!v.oneway) {
                        Edge inv = crearEdge(edgeId++, actual, anterior, v);
                        actual.salientes.add(inv);
                        anterior.entrantes.add(inv);
                        edges.add(inv);
                    }
                }
                anterior = actual;
            }
        }

        for (Edge edge : edges) {
            velocidadMaximaMps = Math.max(velocidadMaximaMps, edge.velocidadLibreMps());
        }

        strtree = new STRtree();
        // Indexar nodos en STRtree
        for (Nodo n : nodos) {
            strtree.insert(new Envelope(n.x, n.x, n.y, n.y), n.id);
        }
        strtree.build();
    }

    private Edge crearEdge(int id, Nodo a, Nodo b, Via v) {
        double len = distanciaMetros(a.lon, a.lat, b.lon, b.lat);
        if (len <= 0) len = 0.01;
        return new Edge(id, a, b, len,
                v.highway, v.name, v.maxspeed, v.oneway, v.lanes, v.ref);
    }

    /** Devuelve el nodo existente o lo crea si no hay uno suficientemente cerca. */
    private Nodo nodoEnOLoCrear(double lon, double lat) {
        SnapKey key = new SnapKey(Math.round(lon / SNAP), Math.round(lat / SNAP));
        Integer existente = snapIndex.get(key);
        if (existente != null) return nodos.get(existente);

        // Normalizado 0..1
        double ancho = maxLon - minLon;
        double alto = maxLat - minLat;
        double nx = ancho > 0 ? (lon - minLon) / ancho : 0;
        double ny = alto > 0 ? (maxLat - lat) / alto : 0;

        Nodo n = new Nodo(nodos.size(), nx, ny, lon, lat);
        nodos.add(n);
        snapIndex.put(key, n.id);
        return n;
    }

    private static boolean esViaVehicular(String highway) {
        return highway != null && TIPOS_VEHICULARES.contains(highway);
    }

    private static double distanciaMetros(double lon1, double lat1, double lon2, double lat2) {
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double dLat = lat2Rad - lat1Rad;
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1Rad) * Math.cos(lat2Rad)
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6_371_000 * Math.asin(Math.sqrt(a));
    }

    /**
     * Nodo mas cercano a (x,y) normalizado 0..1.
     * Usa STRtree para reducir el universo de busqueda.
     */
    public Nodo nodoMasCercano(double nx, double ny) {
        if (nodos.isEmpty()) return null;

        // Buscar primero en un radio razonable
        double radio = 0.0001;
        while (radio < 1.0) {
            Envelope env = new Envelope(nx - radio, nx + radio, ny - radio, ny + radio);
            @SuppressWarnings("unchecked")
            List<Integer> ids = strtree.query(env);
            if (!ids.isEmpty()) {
                Nodo cercano = elegirMasCercano(ids, nx, ny);
                double dx = cercano.x - nx;
                double dy = cercano.y - ny;
                if (dx * dx + dy * dy <= radio * radio) return cercano;
            }
            radio *= 2;
        }
        // Fallback: recorrido lineal
        Nodo mejor = null; double mejorD2 = Double.MAX_VALUE;
        for (Nodo n : nodos) {
            double dx = n.x - nx, dy = n.y - ny;
            double d2 = dx*dx + dy*dy;
            if (d2 < mejorD2) { mejorD2 = d2; mejor = n; }
        }
        return mejor;
    }

    /** Version desde lon/lat. */
    public Nodo nodoMasCercanoLonLat(double lon, double lat) {
        double ancho = maxLon - minLon;
        double alto = maxLat - minLat;
        double nx = ancho > 0 ? (lon - minLon) / ancho : 0;
        double ny = alto > 0 ? (maxLat - lat) / alto : 0;
        return nodoMasCercano(nx, ny);
    }

    public Nodo nodoMasCercanoLonLat(double lon, double lat, Predicate<Edge> edgePermitido) {
        double ancho = maxLon - minLon;
        double alto = maxLat - minLat;
        double nx = ancho > 0 ? (lon - minLon) / ancho : 0;
        double ny = alto > 0 ? (maxLat - lat) / alto : 0;
        double radio = 0.0001;
        while (radio < 1.0) {
            @SuppressWarnings("unchecked")
            List<Integer> ids = strtree.query(new Envelope(
                    nx - radio, nx + radio, ny - radio, ny + radio));
            Nodo cercano = elegirMasCercano(ids, nx, ny, edgePermitido);
            if (cercano != null) {
                double dx = cercano.x - nx;
                double dy = cercano.y - ny;
                if (dx * dx + dy * dy <= radio * radio) return cercano;
            }
            radio *= 2;
        }
        return elegirMasCercano(
                java.util.stream.IntStream.range(0, nodos.size()).boxed().toList(),
                nx, ny, edgePermitido);
    }

    private Nodo elegirMasCercano(List<Integer> ids, double nx, double ny) {
        Nodo mejor = null; double mejorD2 = Double.MAX_VALUE;
        for (int id : ids) {
            Nodo n = nodos.get(id);
            double dx = n.x - nx, dy = n.y - ny;
            double d2 = dx*dx + dy*dy;
            if (d2 < mejorD2) { mejorD2 = d2; mejor = n; }
        }

        return mejor;
    }

    private Nodo elegirMasCercano(List<Integer> ids, double nx, double ny,
                                  Predicate<Edge> edgePermitido) {
        Nodo mejor = null;
        double mejorD2 = Double.MAX_VALUE;
        for (int id : ids) {
            Nodo nodo = nodos.get(id);
            boolean transitivo = nodo.salientes.stream().anyMatch(edgePermitido)
                    || nodo.entrantes.stream().anyMatch(edgePermitido);
            if (!transitivo) continue;
            double dx = nodo.x - nx;
            double dy = nodo.y - ny;
            double distancia2 = dx * dx + dy * dy;
            if (distancia2 < mejorD2) {
                mejorD2 = distancia2;
                mejor = nodo;
            }
        }
        return mejor;
    }

    private record SnapKey(long lon, long lat) {}

    public double getMinLon() { return minLon; }
    public double getMinLat() { return minLat; }
    public double getMaxLon() { return maxLon; }
    public double getMaxLat() { return maxLat; }
    public double getVelocidadMaximaMps() { return velocidadMaximaMps; }
}