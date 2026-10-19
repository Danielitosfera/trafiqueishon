package trafiqueishon.geo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PieroGRutaResult {

    private final List<PieroGEdge> edges;
    private final PieroGNodo inicio;
    private final PieroGNodo fin;

    public PieroGRutaResult(List<PieroGEdge> edges, PieroGNodo inicio, PieroGNodo fin) {
        this.edges = edges == null ? Collections.emptyList() : List.copyOf(edges);
        this.inicio = inicio;
        this.fin = fin;
    }

    public List<PieroGEdge> getEdges() { return edges; }
    public PieroGNodo getInicio() { return inicio; }
    public PieroGNodo getFin() { return fin; }

    public List<PieroGNodo> getNodos() {
        List<PieroGNodo> nodos = new ArrayList<>(edges.size() + 1);
        if (inicio != null) nodos.add(inicio);
        for (PieroGEdge e : edges) nodos.add(e.destino);
        return nodos;
    }

    public double distanciaMetros() {
        double m = 0;
        for (PieroGEdge e : edges) m += e.longitudMetros();
        return m;
    }

    public double tiempoSegundos() {
        double s = 0;
        for (PieroGEdge e : edges) s += e.costoActual();
        return s;
    }

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