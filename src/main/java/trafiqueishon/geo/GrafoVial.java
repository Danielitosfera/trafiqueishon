package trafiqueishon.geo;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.*;

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

    private final List<Nodo> nodos = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private final Map<Long, Integer> snapIndex = new HashMap<>();
    private final STRtree strtree = new STRtree();

    private double minLon, minLat, maxLon, maxLat;

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
        minLon = proyector.getMinLon();
        minLat = proyector.getMinLat();
        maxLon = proyector.getMaxLon();
        maxLat = proyector.getMaxLat();

        int edgeId = 0;
        for (Via v : vias) {
            if (v.coords == null || v.coords.size() < 2) continue;
            if (v.highway == null) continue;

            Nodo anterior = null;
            for (double[] c : v.coords) {
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

        // Indexar nodos en STRtree
        for (Nodo n : nodos) {
            strtree.insert(new Envelope(n.x, n.x, n.y, n.y), n.id);
        }
        strtree.build();
    }

    private Edge crearEdge(int id, Nodo a, Nodo b, Via v) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double len = Math.sqrt(dx*dx + dy*dy);
        if (len <= 0) len = 1e-9;
        return new Edge(id, a, b, len,
                v.highway, v.name, v.maxspeed, v.oneway, v.lanes, v.ref);
    }

    /** Devuelve el nodo existente o lo crea si no hay uno suficientemente cerca. */
    private Nodo nodoEnOLoCrear(double lon, double lat) {
        long key = clave(lon, lat);
        Integer existente = snapIndex.get(key);
        if (existente != null) return nodos.get(existente);

        // Normalizado 0..1
        double nx = (lon - minLon) / (maxLon - minLon);
        double ny = (maxLat - lat) / (maxLat - minLat);

        Nodo n = new Nodo(nodos.size(), nx, ny, lon, lat);
        nodos.add(n);
        snapIndex.put(key, n.id);
        return n;
    }

    private long clave(double lon, double lat) {
        long a = Math.round(lon / SNAP);
        long b = Math.round(lat / SNAP);
        // Mezcla simple
        return a * 1_000_003L + b;
    }

    /**
     * Nodo mas cercano a (x,y) normalizado 0..1.
     * Usa STRtree para reducir el universo de busqueda.
     */
    public Nodo nodoMasCercano(double nx, double ny) {
        if (nodos.isEmpty()) return null;

        // Buscar primero en un radio razonable
        double radio = 0.01;
        while (radio < 1.0) {
            Envelope env = new Envelope(nx - radio, nx + radio, ny - radio, ny + radio);
            @SuppressWarnings("unchecked")
            List<Integer> ids = strtree.query(env);
            if (!ids.isEmpty()) {
                return elegirMasCercano(ids, nx, ny);
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
        double nx = (lon - minLon) / (maxLon - minLon);
        double ny = (maxLat - lat) / (maxLat - minLat);
        return nodoMasCercano(nx, ny);
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

    public double getMinLon() { return minLon; }
    public double getMinLat() { return minLat; }
    public double getMaxLon() { return maxLon; }
    public double getMaxLat() { return maxLat; }
}