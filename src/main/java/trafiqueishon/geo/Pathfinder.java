package trafiqueishon.geo;

import java.util.*;
import java.util.function.Predicate;

/**
 * Buscador de rutas sobre el GrafoVial.
 * Soporta Dijkstra y A* sobre costos de tiempo no negativos.
 * Opcional: penalizacion por giros para rutas mas naturales, y rutas alternativas.
 */
public class Pathfinder {

    public enum Modo { DIJKSTRA, A_ESTRELLA }

    private final GrafoVial grafo;
    private Modo modo = Modo.A_ESTRELLA;

    /** Segundos extra al cambiar de nombre de calle (0 = desactivado). */
    private double penalizacionGiro = 0;

    public Pathfinder(GrafoVial grafo) { this.grafo = Objects.requireNonNull(grafo); }

    public void setModo(Modo m) { this.modo = Objects.requireNonNull(m); }
    public Modo getModo() { return modo; }

    public void setPenalizacionGiro(double segundos) {
        if (!Double.isFinite(segundos) || segundos < 0) {
            throw new IllegalArgumentException("La penalizacion debe ser finita y no negativa.");
        }
        this.penalizacionGiro = segundos;
    }

    /** Ruta optima entre dos nodos, o null si no hay camino. */
    public RutaResult ruta(Nodo inicio, Nodo fin) {
        return ruta(inicio, fin, edge -> true);
    }

    public RutaResult ruta(Nodo inicio, Nodo fin, Predicate<Edge> filtroVial) {
        if (!nodoValido(inicio) || !nodoValido(fin) || filtroVial == null) return null;
        List<Edge> r = buscar(inicio, fin, Collections.emptyMap(), filtroVial);
        return r == null ? null : new RutaResult(r, inicio, fin);
    }

    /**
     * Calcula una ruta que atraviesa los nodos obligatorios en el orden recibido.
     * Cada tramo se optimiza de forma independiente con el mismo filtro vial.
     */
    public RutaResult ruta(List<Nodo> waypoints) {
        return ruta(waypoints, edge -> true);
    }

    public RutaResult ruta(List<Nodo> waypoints, Predicate<Edge> filtroVial) {
        if (waypoints == null || waypoints.size() < 2 || filtroVial == null
                || waypoints.stream().anyMatch(nodo -> !nodoValido(nodo))) {
            return null;
        }
        List<Edge> recorrido = new ArrayList<>();
        for (int i = 1; i < waypoints.size(); i++) {
            Nodo inicio = waypoints.get(i - 1);
            Nodo fin = waypoints.get(i);
            List<Edge> tramo = buscar(inicio, fin, Collections.emptyMap(), filtroVial);
            if (tramo == null) return null;
            recorrido.addAll(tramo);
        }
        return new RutaResult(recorrido, waypoints.getFirst(), waypoints.getLast());
    }

    /**
     * Hasta k rutas alternativas distintas.
     * Despues de cada ruta, sus tramos se penalizan para forzar caminos distintos.
     */
    public List<RutaResult> rutasAlternativas(Nodo inicio, Nodo fin, int k) {
        if (k < 0) throw new IllegalArgumentException("La cantidad de rutas no puede ser negativa.");
        if (!nodoValido(inicio) || !nodoValido(fin)) return List.of();
        List<RutaResult> resultado = new ArrayList<>();
        Set<List<Integer>> firmas = new HashSet<>();
        Map<String, Double> penalizaciones = new HashMap<>();

        for (int i = 0; i < k; i++) {
            List<Edge> r = buscar(inicio, fin, penalizaciones, edge -> true);
            if (r == null || r.isEmpty()) break;
            List<Integer> firma = r.stream().map(edge -> edge.id).toList();
            if (!firmas.add(firma)) break;
            resultado.add(new RutaResult(r, inicio, fin));

            // Penalizar ambos sentidos de cada tramo usado
            for (Edge e : r) {
                String ida = e.origen.id + ":" + e.destino.id;
                String vuelta = e.destino.id + ":" + e.origen.id;
                penalizaciones.merge(ida, e.costoActual() * 0.6, Double::sum);
                penalizaciones.merge(vuelta, e.costoActual() * 0.6, Double::sum);
            }
        }
        return resultado;
    }

    /** Nucleo Dijkstra/A* con penalizaciones externas opcionales. */
    private List<Edge> buscar(Nodo inicio, Nodo fin, Map<String, Double> penalizaciones,
                              Predicate<Edge> filtroVial) {
        if (!nodoValido(inicio) || !nodoValido(fin) || filtroVial == null) return null;
        if (inicio == fin) return Collections.emptyList();

        List<Edge> edges = grafo.getEdges();
        int estadoInicial = edges.size();
        int numeroEstados = edges.size() + 1;
        double[] g = new double[numeroEstados];
        Arrays.fill(g, Double.POSITIVE_INFINITY);
        double[] f = new double[numeroEstados];
        Arrays.fill(f, Double.POSITIVE_INFINITY);
        Edge[] cameFrom = new Edge[numeroEstados];
        int[] estadoAnterior = new int[numeroEstados];
        Arrays.fill(estadoAnterior, -1);
        boolean[] cerrado = new boolean[numeroEstados];

        g[estadoInicial] = 0;
        f[estadoInicial] = (modo == Modo.A_ESTRELLA) ? heuristica(inicio, fin) : 0;

        PriorityQueue<EntradaCola> abierta = new PriorityQueue<>(
                Comparator.comparingDouble(EntradaCola::prioridad));
        abierta.add(new EntradaCola(estadoInicial, f[estadoInicial]));

        while (!abierta.isEmpty()) {
            int estado = abierta.poll().estado();
            if (cerrado[estado]) continue;
            Nodo actual = estado == estadoInicial
                    ? inicio
                    : edges.get(estado).destino;

            if (actual == fin) return reconstruir(cameFrom, estadoAnterior, estado, estadoInicial);
            cerrado[estado] = true;
            Edge previa = estado == estadoInicial ? null : edges.get(estado);
            String nombrePrevio = (previa != null) ? previa.name : null;

            for (Edge e : actual.salientes) {
                if (!filtroVial.test(e)) continue;
                int siguienteEstado = e.id;
                if (cerrado[siguienteEstado]) continue;

                double costo = e.costoActual()
                        + penalizaciones.getOrDefault(
                                e.origen.id + ":" + e.destino.id, 0.0);

                // Penalizar cambio de calle (solo si ambas tienen nombre)
                if (penalizacionGiro > 0 && nombrePrevio != null && e.name != null
                        && !e.name.equals(nombrePrevio)) {
                    costo += penalizacionGiro;
                }

                double tentativa = g[estado] + costo;
                if (tentativa < g[siguienteEstado]) {
                    cameFrom[siguienteEstado] = e;
                    estadoAnterior[siguienteEstado] = estado;
                    g[siguienteEstado] = tentativa;
                    f[siguienteEstado] = tentativa
                            + ((modo == Modo.A_ESTRELLA) ? heuristica(e.destino, fin) : 0);
                    abierta.add(new EntradaCola(siguienteEstado, f[siguienteEstado]));
                }
            }
        }
        return null;
    }

    private boolean nodoValido(Nodo nodo) {
        return nodo != null && nodo.id >= 0 && nodo.id < grafo.numNodos()
                && grafo.getNodos().get(nodo.id) == nodo;
    }

    private List<Edge> reconstruir(Edge[] cameFrom, int[] estadoAnterior,
                                   int estadoFinal, int estadoInicial) {
        LinkedList<Edge> ruta = new LinkedList<>();
        int estado = estadoFinal;
        while (estado != estadoInicial) {
            Edge e = cameFrom[estado];
            if (e == null) return Collections.emptyList();
            ruta.addFirst(e);
            estado = estadoAnterior[estado];
        }
        return ruta;
    }

    private double heuristica(Nodo a, Nodo b) {
        double lat1 = Math.toRadians(a.lat);
        double lat2 = Math.toRadians(b.lat);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.lon - a.lon);
        double sinLat = Math.sin(dLat / 2);
        double sinLon = Math.sin(dLon / 2);
        double h = sinLat * sinLat
                + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        double distancia = 2 * 6_371_000 * Math.asin(Math.sqrt(h));
        return distancia / grafo.getVelocidadMaximaMps();
    }

    private record EntradaCola(int estado, double prioridad) {}
}
