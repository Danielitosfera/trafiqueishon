package trafiqueishon.geo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resultado de una busqueda de ruta (Dijkstra o A*).
 * Incluye la lista de edges, los nodos y metricas resumidas.
 */
public class RutaResult {

    private final List<Edge> edges;
    private final Nodo inicio;
    private final Nodo fin;

    public RutaResult(List<Edge> edges, Nodo inicio, Nodo fin) {
        this.edges = edges == null ? Collections.emptyList() : List.copyOf(edges);
        this.inicio = inicio;
        this.fin = fin;
    }

    public List<Edge> getEdges() { return edges; }
    public Nodo getInicio() { return inicio; }
    public Nodo getFin() { return fin; }

    /** Nodos de la ruta, incluyendo el origen. */
    public List<Nodo> getNodos() {
        List<Nodo> nodos = new ArrayList<>(edges.size() + 1);
        if (inicio != null) nodos.add(inicio);
        for (Edge e : edges) nodos.add(e.destino);
        return nodos;
    }

    /** Distancia total en metros. */
    public double distanciaMetros() {
        double m = 0;
        for (Edge e : edges) m += e.longitudMetros();
        return m;
    }

    /** Tiempo estimado en segundos (aplica congestion). */
    public double tiempoSegundos() {
        double s = 0;
        for (Edge e : edges) s += e.costoActual();
        return s;
    }

    /** Resumen legible: "1.24 km - 3 min 15 s - 12 tramos". */
    public String resumen() {
        double km = distanciaMetros() / 1000.0;
        double seg = tiempoSegundos();
        int min = (int) (seg / 60);
        int s = (int) Math.round(seg % 60);
        return String.format("%.2f km  |  %d min %02d s  |  %d tramos",
                km, min, s, edges.size());
    }

    public boolean esVacia() { return edges.isEmpty(); }
}
