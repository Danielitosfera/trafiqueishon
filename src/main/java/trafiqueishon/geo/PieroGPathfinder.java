package trafiqueishon.geo;

import java.util.*;
import java.util.function.Predicate;

public class PieroGPathfinder {

    public enum Modo { DIJKSTRA, A_ESTRELLA }

    private final PieroGGrafoVial grafo;
    private Modo modo = Modo.A_ESTRELLA;
    private double penalizacionGiro = 0;
    /** Segundos extra por dar media vuelta en una intersección que ofrece otras salidas. */
    private double penalizacionGiroEnU = 60;

    public PieroGPathfinder(PieroGGrafoVial grafo) {
        this.grafo = Objects.requireNonNull(grafo);
    }

    public void setPenalizacionGiroEnU(double segundos) {
        if (!Double.isFinite(segundos) || segundos < 0) {
            throw new IllegalArgumentException("La penalizacion debe ser finita y no negativa.");
        }
        this.penalizacionGiroEnU = segundos;
    }

    public void setModo(Modo m) { this.modo = Objects.requireNonNull(m); }
    public Modo getModo() { return modo; }

    public void setPenalizacionGiro(double segundos) {
        if (!Double.isFinite(segundos) || segundos < 0) {
            throw new IllegalArgumentException("La penalizacion debe ser finita y no negativa.");
        }
        this.penalizacionGiro = segundos;
    }

    public PieroGRutaResult ruta(PieroGNodo inicio, PieroGNodo fin) {
        return ruta(inicio, fin, edge -> !edge.cerrado);
    }

    public PieroGRutaResult ruta(PieroGNodo inicio, PieroGNodo fin, Predicate<PieroGEdge> filtroVial) {
        if (!nodoValido(inicio) || !nodoValido(fin) || filtroVial == null) return null;
        List<PieroGEdge> r = buscar(inicio, fin, Collections.emptyMap(), filtroVial);
        return r == null ? null : new PieroGRutaResult(r, inicio, fin);
    }

    /**
     * Ruta que no se aleja más de {@code radioMetros} de la ruta {@code referencia}
     * (un desvío corto por calles paralelas). Devuelve {@code null} si dentro de ese
     * corredor no existe camino; el llamador decide si relaja el radio.
     */
    public PieroGRutaResult rutaCercana(PieroGNodo inicio, PieroGNodo fin,
                                        List<PieroGEdge> referencia, double radioMetros) {
        if (!nodoValido(inicio) || !nodoValido(fin) || referencia == null || referencia.isEmpty()
                || !(radioMetros > 0)) {
            return null;
        }
        double latMedia = referencia.getFirst().origen.lat;
        double metrosLat = 111_320;
        double metrosLon = metrosLat * Math.max(0.01, Math.cos(Math.toRadians(latMedia)));
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (PieroGEdge r : referencia) {
            for (PieroGNodo n : new PieroGNodo[]{r.origen, r.destino}) {
                minX = Math.min(minX, n.lon * metrosLon);
                maxX = Math.max(maxX, n.lon * metrosLon);
                minY = Math.min(minY, n.lat * metrosLat);
                maxY = Math.max(maxY, n.lat * metrosLat);
            }
        }
        final double cMinX = minX - radioMetros, cMaxX = maxX + radioMetros;
        final double cMinY = minY - radioMetros, cMaxY = maxY + radioMetros;
        Predicate<PieroGEdge> corredor = edge -> {
            if (edge.cerrado) return false;
            double px = edge.destino.lon * metrosLon, py = edge.destino.lat * metrosLat;
            if (px < cMinX || px > cMaxX || py < cMinY || py > cMaxY) return false;
            for (PieroGEdge r : referencia) {
                if (distanciaPuntoSegmento(px, py, r.origen.lon * metrosLon, r.origen.lat * metrosLat,
                        r.destino.lon * metrosLon, r.destino.lat * metrosLat) <= radioMetros) {
                    return true;
                }
            }
            return false;
        };
        return ruta(inicio, fin, corredor);
    }

    private static double distanciaPuntoSegmento(double px, double py, double ax, double ay,
                                                 double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.clamp(((px - ax) * dx + (py - ay) * dy) / l2, 0, 1);
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    public Map<PieroGNodo, Double> costosMinimosDesde(PieroGNodo inicio) {
        if (!nodoValido(inicio)) return Map.of();
        Map<PieroGNodo, Double> costos = new IdentityHashMap<>();
        Set<PieroGNodo> visitados = Collections.newSetFromMap(new IdentityHashMap<>());
        PriorityQueue<EntradaNodo> cola = new PriorityQueue<>(
                Comparator.comparingDouble(EntradaNodo::costo));
        costos.put(inicio, 0.0);
        cola.add(new EntradaNodo(inicio, 0));
        while (!cola.isEmpty()) {
            EntradaNodo actual = cola.poll();
            if (!visitados.add(actual.nodo())) continue;
            for (PieroGEdge edge : actual.nodo().salientes) {
                if (edge.cerrado) continue;
                double peso = edge.costoActual();
                if (!Double.isFinite(peso) || peso < 0) continue;
                double nuevoCosto = actual.costo() + peso;
                if (nuevoCosto < costos.getOrDefault(edge.destino, Double.POSITIVE_INFINITY)) {
                    costos.put(edge.destino, nuevoCosto);
                    cola.add(new EntradaNodo(edge.destino, nuevoCosto));
                }
            }
        }
        return Collections.unmodifiableMap(costos);
    }

    public PieroGRutaResult ruta(List<PieroGNodo> waypoints) {
        return ruta(waypoints, edge -> true);
    }

    public PieroGRutaResult ruta(List<PieroGNodo> waypoints, Predicate<PieroGEdge> filtroVial) {
        if (waypoints == null || waypoints.size() < 2 || filtroVial == null
                || waypoints.stream().anyMatch(nodo -> !nodoValido(nodo))) {
            return null;
        }
        List<PieroGEdge> recorrido = new ArrayList<>();
        for (int i = 1; i < waypoints.size(); i++) {
            PieroGNodo inicio = waypoints.get(i - 1);
            PieroGNodo fin = waypoints.get(i);
            List<PieroGEdge> tramo = buscar(inicio, fin, Collections.emptyMap(), filtroVial);
            if (tramo == null) return null;
            recorrido.addAll(tramo);
        }
        return new PieroGRutaResult(recorrido, waypoints.getFirst(), waypoints.getLast());
    }

    public List<PieroGRutaResult> rutasAlternativas(PieroGNodo inicio, PieroGNodo fin, int k) {
        if (k < 0) throw new IllegalArgumentException("La cantidad de rutas no puede ser negativa.");
        if (!nodoValido(inicio) || !nodoValido(fin)) return List.of();
        List<PieroGRutaResult> resultado = new ArrayList<>();
        Set<List<Integer>> firmas = new HashSet<>();
        Map<String, Double> penalizaciones = new HashMap<>();

        for (int i = 0; i < k; i++) {
            List<PieroGEdge> r = buscar(inicio, fin, penalizaciones, edge -> !edge.cerrado);
            if (r == null || r.isEmpty()) break;
            List<Integer> firma = r.stream().map(edge -> edge.id).toList();
            if (!firmas.add(firma)) break;
            resultado.add(new PieroGRutaResult(r, inicio, fin));

            for (PieroGEdge e : r) {
                String ida = e.origen.id + ":" + e.destino.id;
                String vuelta = e.destino.id + ":" + e.origen.id;
                penalizaciones.merge(ida, e.costoActual() * 0.6, Double::sum);
                penalizaciones.merge(vuelta, e.costoActual() * 0.6, Double::sum);
            }
        }
        return resultado;
    }

    private List<PieroGEdge> buscar(PieroGNodo inicio, PieroGNodo fin, Map<String, Double> penalizaciones,
                              Predicate<PieroGEdge> filtroVial) {
        if (!nodoValido(inicio) || !nodoValido(fin) || filtroVial == null) return null;
        if (inicio == fin) return Collections.emptyList();

        List<PieroGEdge> edges = grafo.getEdges();
        int estadoInicial = edges.size();
        int numeroEstados = edges.size() + 1;
        double[] g = new double[numeroEstados];
        Arrays.fill(g, Double.POSITIVE_INFINITY);
        double[] f = new double[numeroEstados];
        Arrays.fill(f, Double.POSITIVE_INFINITY);
        PieroGEdge[] cameFrom = new PieroGEdge[numeroEstados];
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
            PieroGNodo actual = estado == estadoInicial ? inicio : edges.get(estado).destino;

            if (actual == fin) return reconstruir(cameFrom, estadoAnterior, estado, estadoInicial);
            cerrado[estado] = true;
            PieroGEdge previa = estado == estadoInicial ? null : edges.get(estado);
            String nombrePrevio = (previa != null) ? previa.name : null;

            for (PieroGEdge e : actual.salientes) {
                if (!filtroVial.test(e)) continue;
                int siguienteEstado = e.id;
                if (cerrado[siguienteEstado]) continue;

                double costo = e.costoActual()
                        + penalizaciones.getOrDefault(
                        e.origen.id + ":" + e.destino.id, 0.0);

                // Girar en U solo es natural en un callejón sin salida.
                if (previa != null && e.destino == previa.origen && actual.salientes.size() > 1) {
                    costo += penalizacionGiroEnU;
                }

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

    private boolean nodoValido(PieroGNodo nodo) {
        return nodo != null && nodo.id >= 0 && nodo.id < grafo.numNodos()
                && grafo.getNodos().get(nodo.id) == nodo;
    }

    private List<PieroGEdge> reconstruir(PieroGEdge[] cameFrom, int[] estadoAnterior,
                                   int estadoFinal, int estadoInicial) {
        LinkedList<PieroGEdge> ruta = new LinkedList<>();
        int estado = estadoFinal;
        while (estado != estadoInicial) {
            PieroGEdge e = cameFrom[estado];
            if (e == null) return Collections.emptyList();
            ruta.addFirst(e);
            estado = estadoAnterior[estado];
        }
        return ruta;
    }

    private double heuristica(PieroGNodo a, PieroGNodo b) {
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
    private record EntradaNodo(PieroGNodo nodo, double costo) {}
}