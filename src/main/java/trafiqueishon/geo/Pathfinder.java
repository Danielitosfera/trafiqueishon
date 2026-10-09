package trafiqueishon.geo;

import java.util.*;

/**
 * A* sobre el GrafoVial.
 * Usa Edge.costoBase (segundos) como peso.
 * Heuristica: distancia euclidiana normalizada / velocidad maxima posible.
 */
public class Pathfinder {

    private static final double VEL_MAX_MPS = 80.0 / 3.6;    // 80 km/h
    private static final double ESCALA_METROS = 111_000.0;   // 0..1 -> metros

    private final GrafoVial grafo;

    public Pathfinder(GrafoVial grafo) { this.grafo = grafo; }

    /**
     * Devuelve la lista de Edge que forman la ruta optima,
     * o null si no hay camino.
     */
    public List<Edge> ruta(Nodo inicio, Nodo fin) {
        if (inicio == null || fin == null) return null;
        if (inicio == fin) return Collections.emptyList();

        int n = grafo.getNodos().size();
        double[] g = new double[n];
        Arrays.fill(g, Double.POSITIVE_INFINITY);
        double[] f = new double[n];
        Arrays.fill(f, Double.POSITIVE_INFINITY);
        Edge[] cameFrom = new Edge[n];
        boolean[] cerrado = new boolean[n];

        g[inicio.id] = 0;
        f[inicio.id] = heuristica(inicio, fin);

        PriorityQueue<Integer> abierta = new PriorityQueue<>(
                Comparator.comparingDouble(a -> f[a]));
        abierta.add(inicio.id);

        while (!abierta.isEmpty()) {
            int actualId = abierta.poll();
            if (cerrado[actualId]) continue;
            cerrado[actualId] = true;

            if (actualId == fin.id) {
                return reconstruir(cameFrom, fin);
            }

            Nodo actual = grafo.getNodos().get(actualId);
            for (Edge e : actual.salientes) {
                int vecinoId = e.destino.id;
                if (cerrado[vecinoId]) continue;

                double tentativa = g[actualId] + e.costoBase;
                if (tentativa < g[vecinoId]) {
                    cameFrom[vecinoId] = e;
                    g[vecinoId] = tentativa;
                    f[vecinoId] = tentativa + heuristica(e.destino, fin);
                    abierta.add(vecinoId);
                }
            }
        }
        return null; // no hay ruta
    }

    /** Reconstruye la ruta desde cameFrom. */
    private List<Edge> reconstruir(Edge[] cameFrom, Nodo fin) {
        LinkedList<Edge> ruta = new LinkedList<>();
        Nodo actual = fin;
        while (actual != null) {
            Edge e = cameFrom[actual.id];
            if (e == null) break;
            ruta.addFirst(e);
            actual = e.origen;
        }
        return ruta;
    }

    /**
     * Heuristica admisible: distancia euclidiana normalizada -> metros,
     * dividida por la velocidad maxima posible.
     */
    private double heuristica(Nodo a, Nodo b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double distNorm = Math.sqrt(dx * dx + dy * dy);
        double distMetros = distNorm * ESCALA_METROS;
        return distMetros / VEL_MAX_MPS;
    }
}