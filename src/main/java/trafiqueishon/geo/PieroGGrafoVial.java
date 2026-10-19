package trafiqueishon.geo;

import trafiqueishon.simulacion.EberthMSemaforo;

import java.util.*;
import java.util.function.Predicate;

public class PieroGGrafoVial {

    private static final double SNAP = 1e-6;
    private static final Set<String> TIPOS_VEHICULARES = Set.of(
            "motorway", "motorway_link", "trunk", "trunk_link",
            "primary", "primary_link", "secondary", "secondary_link",
            "tertiary", "tertiary_link", "unclassified", "residential",
            "living_street", "service", "road");

    private final List<PieroGNodo> nodos = new ArrayList<>();
    private final List<PieroGEdge> edges = new ArrayList<>();
    private MapaLongInt snapIndex = new MapaLongInt(16);
    private RejillaNodos rejilla = RejillaNodos.vacia();

    private static final double RADIO_PUNTO_CRITICO_M = 50.0;
    private static final double SEPARACION_SEMAFOROS_M = 150.0;
    private final List<PieroGNodo> interseccionesAvenidas = new ArrayList<>();
    private volatile List<PieroGNodo> semaforosActuales = List.of();
    private volatile int versionSemaforos;

    private double minLon, minLat, maxLon, maxLat;
    private double velocidadMaximaMps = 1.0;

    public List<PieroGNodo> getNodos() { return nodos; }
    public List<PieroGEdge> getEdges() { return edges; }
    public int numNodos() { return nodos.size(); }
    public int numEdges() { return edges.size(); }

    public String resumen() {
        return "GrafoVial[nodos=" + nodos.size() + ", edges=" + edges.size() + "]";
    }

    public void construir(List<PieroGVia> vias, PieroGProyector proyector) {
        nodos.clear();
        edges.clear();
        int estimacion = 0;
        for (PieroGVia v : vias) if (v.coords != null) estimacion += v.coords.size();
        snapIndex = new MapaLongInt(Math.max(16, estimacion));
        velocidadMaximaMps = 1.0;
        minLon = proyector.getMinLon();
        minLat = proyector.getMinLat();
        maxLon = proyector.getMaxLon();
        maxLat = proyector.getMaxLat();

        int edgeId = 0;
        for (PieroGVia v : vias) {
            if (v.coords == null || v.coords.size() < 2) continue;
            if (!esViaVehicular(v.highway)) continue;

            PieroGNodo anterior = null;
            for (int i = 0; i < v.coords.size(); i++) {
                int indice = v.reverseOneway ? v.coords.size() - i - 1 : i;
                double[] c = v.coords.get(indice);
                PieroGNodo actual = nodoEnOLoCrear(c[0], c[1]);
                if (anterior != null && anterior.id != actual.id) {
                    PieroGEdge e = crearEdge(edgeId++, anterior, actual, v);
                    anterior.salientes.add(e);
                    actual.entrantes.add(e);
                    edges.add(e);

                    if (!v.oneway) {
                        PieroGEdge inv = crearEdge(edgeId++, actual, anterior, v);
                        actual.salientes.add(inv);
                        anterior.entrantes.add(inv);
                        edges.add(inv);
                    }
                }
                anterior = actual;
            }
        }

        for (PieroGEdge edge : edges) {
            velocidadMaximaMps = Math.max(velocidadMaximaMps, edge.velocidadLibreMps());
        }

        interseccionesAvenidas.clear();
        semaforosActuales = List.of();
        for (PieroGNodo n : nodos) {
            n.compactar();
            if (esInterseccionDeAvenidas(n)) interseccionesAvenidas.add(n);
        }
        ((ArrayList<PieroGNodo>) nodos).trimToSize();
        ((ArrayList<PieroGEdge>) edges).trimToSize();
        rejilla = RejillaNodos.construir(nodos);
        snapIndex = new MapaLongInt(16);
        regenerarSemaforos(List.of());
    }

    private static boolean esAvenida(String highway) {
        return "primary".equals(highway) || "secondary".equals(highway);
    }

    /**
     * Intersección de al menos dos avenidas distintas. Las vías se distinguen por nombre
     * (o ref); si no tienen, hacen falta al menos tres vecinos de avenida (cruce o T).
     */
    private static boolean esInterseccionDeAvenidas(PieroGNodo n) {
        Set<String> vias = new HashSet<>();
        Set<PieroGNodo> vecinos = new HashSet<>();
        for (int i = 0; i < n.salientes.size() + n.entrantes.size(); i++) {
            boolean saliente = i < n.salientes.size();
            PieroGEdge e = saliente ? n.salientes.get(i) : n.entrantes.get(i - n.salientes.size());
            if (!esAvenida(e.highway)) continue;
            vecinos.add(saliente ? e.destino : e.origen);
            String clave = e.name != null && !e.name.isBlank() ? e.name : e.ref;
            if (clave != null && !clave.isBlank()) vias.add(clave);
        }
        return vias.size() >= 2 || vecinos.size() >= 3;
    }

    /** Nodos con semáforo vigentes (copia ordenada por x, lista para recortar al viewport). */
    public List<PieroGNodo> getNodosConSemaforo() { return semaforosActuales; }

    /** Cambia cada vez que se regeneran los semáforos; los dibujos en caché lo comparan. */
    public int getVersionSemaforos() { return versionSemaforos; }

    /**
     * Recalcula qué nodos tienen semáforo: los más cercanos (hasta 50 m) a cada punto crítico
     * y las intersecciones de avenidas, descartando los que queden a menos de 150 m de otro.
     *
     * @param criticos puntos {@code {lon, lat}} de hospitales, colegios y mercados
     */
    public synchronized void regenerarSemaforos(List<double[]> criticos) {
        for (PieroGNodo n : semaforosActuales) n.semaforo = null;
        List<PieroGNodo> candidatos = new ArrayList<>();
        for (double[] punto : criticos) {
            PieroGNodo n = nodoMasCercanoLonLat(punto[0], punto[1]);
            if (n != null && distanciaMetros(punto[0], punto[1], n.lon, n.lat) <= RADIO_PUNTO_CRITICO_M) {
                candidatos.add(n);
            }
        }
        candidatos.addAll(interseccionesAvenidas);

        double celdaLat = SEPARACION_SEMAFOROS_M / 111_320.0;
        double celdaLon = celdaLat / Math.max(0.1, Math.cos(Math.toRadians((minLat + maxLat) / 2)));
        Map<Long, List<PieroGNodo>> celdas = new HashMap<>();
        List<PieroGNodo> elegidos = new ArrayList<>();
        for (PieroGNodo c : candidatos) {
            if (c.semaforo != null) continue;
            long cx = (long) Math.floor(c.lon / celdaLon), cy = (long) Math.floor(c.lat / celdaLat);
            boolean cerca = false;
            for (long dx = -1; dx <= 1 && !cerca; dx++) {
                for (long dy = -1; dy <= 1 && !cerca; dy++) {
                    List<PieroGNodo> vecinos = celdas.get(((cx + dx) << 32) ^ ((cy + dy) & 0xffffffffL));
                    if (vecinos == null) continue;
                    for (PieroGNodo v : vecinos) {
                        if (distanciaMetros(c.lon, c.lat, v.lon, v.lat) < SEPARACION_SEMAFOROS_M) {
                            cerca = true;
                            break;
                        }
                    }
                }
            }
            if (cerca) continue;
            c.semaforo = new EberthMSemaforo(c.id);
            celdas.computeIfAbsent((cx << 32) ^ (cy & 0xffffffffL), k -> new ArrayList<>()).add(c);
            elegidos.add(c);
        }
        elegidos.sort(Comparator.comparingDouble(n -> n.x));
        semaforosActuales = List.copyOf(elegidos);
        versionSemaforos++;
    }

    private PieroGEdge crearEdge(int id, PieroGNodo a, PieroGNodo b, PieroGVia v) {
        double len = distanciaMetros(a.lon, a.lat, b.lon, b.lat);
        if (len <= 0) len = 0.01;
        return new PieroGEdge(id, a, b, len,
                v.highway, v.name, v.maxspeed, v.oneway, v.lanes, v.ref);
    }

    private PieroGNodo nodoEnOLoCrear(double lon, double lat) {
        long key = (Math.round(lon / SNAP) << 32) ^ (Math.round(lat / SNAP) & 0xffffffffL);
        int existente = snapIndex.get(key);
        if (existente >= 0) return nodos.get(existente);

        double ancho = maxLon - minLon;
        double alto = maxLat - minLat;
        double nx = ancho > 0 ? (lon - minLon) / ancho : 0;
        double ny = alto > 0 ? (maxLat - lat) / alto : 0;

        PieroGNodo n = new PieroGNodo(nodos.size(), nx, ny, lon, lat);
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

    public PieroGNodo nodoMasCercano(double nx, double ny) {
        if (nodos.isEmpty()) return null;
        return rejilla.masCercano(nodos, nx, ny, null);
    }

    public PieroGNodo nodoMasCercanoLonLat(double lon, double lat) {
        double ancho = maxLon - minLon;
        double alto = maxLat - minLat;
        double nx = ancho > 0 ? (lon - minLon) / ancho : 0;
        double ny = alto > 0 ? (maxLat - lat) / alto : 0;
        return nodoMasCercano(nx, ny);
    }

    /**
     * Nodo más cercano más sus vecinos inmediatos que estén casi igual de cerca
     * (hasta {@code margenMetros} más lejos). Sirven como alternativas de ajuste
     * cuando el nodo más cercano queda en el lado equivocado de la calle.
     */
    public List<PieroGNodo> nodosCandidatosLonLat(double lon, double lat, double margenMetros, int maximo) {
        PieroGNodo base = nodoMasCercanoLonLat(lon, lat);
        if (base == null) return List.of();
        double limite = distanciaMetros(lon, lat, base.lon, base.lat) + margenMetros;
        List<PieroGNodo> vecinos = new ArrayList<>();
        for (PieroGEdge e : base.salientes) vecinos.add(e.destino);
        for (PieroGEdge e : base.entrantes) vecinos.add(e.origen);
        vecinos.sort(Comparator.comparingDouble(n -> distanciaMetros(lon, lat, n.lon, n.lat)));
        List<PieroGNodo> resultado = new ArrayList<>();
        resultado.add(base);
        for (PieroGNodo n : vecinos) {
            if (resultado.size() >= maximo) break;
            if (!resultado.contains(n) && distanciaMetros(lon, lat, n.lon, n.lat) <= limite) {
                resultado.add(n);
            }
        }
        return resultado;
    }

    public PieroGNodo nodoMasCercanoLonLat(double lon, double lat, Predicate<PieroGEdge> edgePermitido) {
        if (nodos.isEmpty()) return null;
        double ancho = maxLon - minLon;
        double alto = maxLat - minLat;
        double nx = ancho > 0 ? (lon - minLon) / ancho : 0;
        double ny = alto > 0 ? (maxLat - lat) / alto : 0;
        return rejilla.masCercano(nodos, nx, ny, nodo ->
                nodo.salientes.stream().anyMatch(edgePermitido)
                        || nodo.entrantes.stream().anyMatch(edgePermitido));
    }

    /**
     * Mapa long→int de direccionamiento abierto (sin objetos por entrada).
     * Sustituye a {@code HashMap<SnapKey,Integer>} durante la construcción:
     * en Lima son millones de vértices y cada entrada boxeada costaba ~70 bytes.
     */
    static final class MapaLongInt {
        private long[] claves;
        private int[] valores;
        private int tamano;

        MapaLongInt(int capacidadEsperada) {
            int capacidad = Integer.highestOneBit(Math.max(4, capacidadEsperada * 2 - 1)) << 1;
            claves = new long[capacidad];
            valores = new int[capacidad];
            Arrays.fill(valores, -1);
        }

        int get(long clave) {
            int mascara = claves.length - 1;
            for (int i = mezclar(clave) & mascara; ; i = (i + 1) & mascara) {
                if (valores[i] < 0) return -1;
                if (claves[i] == clave) return valores[i];
            }
        }

        void put(long clave, int valor) {
            if ((tamano + 1) * 2 > claves.length) crecer();
            int mascara = claves.length - 1;
            for (int i = mezclar(clave) & mascara; ; i = (i + 1) & mascara) {
                if (valores[i] < 0) {
                    claves[i] = clave;
                    valores[i] = valor;
                    tamano++;
                    return;
                }
                if (claves[i] == clave) {
                    valores[i] = valor;
                    return;
                }
            }
        }

        private void crecer() {
            long[] viejasClaves = claves;
            int[] viejosValores = valores;
            claves = new long[viejasClaves.length * 2];
            valores = new int[viejasClaves.length * 2];
            Arrays.fill(valores, -1);
            tamano = 0;
            for (int i = 0; i < viejasClaves.length; i++) {
                if (viejosValores[i] >= 0) put(viejasClaves[i], viejosValores[i]);
            }
        }

        private static int mezclar(long clave) {
            long h = clave * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 32));
        }
    }

    /**
     * Rejilla uniforme de nodos en coordenadas normalizadas, en formato CSR
     * (dos {@code int[]}). Búsqueda del más cercano por anillos crecientes.
     * Ocupa ~8 bytes por nodo frente a los ~100 de un STRtree con Envelope.
     */
    static final class RejillaNodos {
        private final int lado;
        private final int[] inicioCelda;
        private final int[] ids;

        private RejillaNodos(int lado, int[] inicioCelda, int[] ids) {
            this.lado = lado;
            this.inicioCelda = inicioCelda;
            this.ids = ids;
        }

        static RejillaNodos vacia() {
            return new RejillaNodos(1, new int[]{0, 0}, new int[0]);
        }

        static RejillaNodos construir(List<PieroGNodo> nodos) {
            int lado = Math.clamp(Math.round(Math.sqrt(nodos.size() / 2.0)), 1, 4096);
            int celdas = lado * lado;
            int[] conteo = new int[celdas + 1];
            for (PieroGNodo n : nodos) conteo[celda(lado, n.x, n.y) + 1]++;
            for (int i = 0; i < celdas; i++) conteo[i + 1] += conteo[i];
            int[] ids = new int[nodos.size()];
            int[] cursor = Arrays.copyOf(conteo, celdas);
            for (PieroGNodo n : nodos) ids[cursor[celda(lado, n.x, n.y)]++] = n.id;
            return new RejillaNodos(lado, conteo, ids);
        }

        private static int celda(int lado, double x, double y) {
            int cx = Math.clamp((long) Math.floor(x * lado), 0, lado - 1);
            int cy = Math.clamp((long) Math.floor(y * lado), 0, lado - 1);
            return cy * lado + cx;
        }

        /** PieroGNodo más cercano a (nx, ny) que cumpla el filtro (o {@code null} si ninguno). */
        PieroGNodo masCercano(List<PieroGNodo> nodos, double nx, double ny,
                        java.util.function.Predicate<PieroGNodo> filtro) {
            int cx = Math.clamp((long) Math.floor(nx * lado), 0, lado - 1);
            int cy = Math.clamp((long) Math.floor(ny * lado), 0, lado - 1);
            double tamCelda = 1.0 / lado;
            PieroGNodo mejor = null;
            double mejorD2 = Double.MAX_VALUE;
            for (int r = 0; r <= lado; r++) {
                // Cualquier nodo fuera del anillo r está al menos a (r - ~1) celdas
                // del punto; si ya hay uno más cerca que eso, se puede parar.
                double distanciaMinimaAnillo = Math.max(0, (r - 1)) * tamCelda;
                if (mejor != null && distanciaMinimaAnillo * distanciaMinimaAnillo > mejorD2) break;
                for (int y = cy - r; y <= cy + r; y++) {
                    if (y < 0 || y >= lado) continue;
                    boolean borde = y == cy - r || y == cy + r;
                    for (int x = cx - r; x <= cx + r; x += borde ? 1 : Math.max(1, 2 * r)) {
                        if (x < 0 || x >= lado) continue;
                        int c = y * lado + x;
                        for (int k = inicioCelda[c]; k < inicioCelda[c + 1]; k++) {
                            PieroGNodo n = nodos.get(ids[k]);
                            double dx = n.x - nx, dy = n.y - ny;
                            double d2 = dx * dx + dy * dy;
                            if (d2 < mejorD2 && (filtro == null || filtro.test(n))) {
                                mejorD2 = d2;
                                mejor = n;
                            }
                        }
                    }
                }
            }
            return mejor;
        }
    }

    public double getMinLon() { return minLon; }
    public double getMinLat() { return minLat; }
    public double getMaxLon() { return maxLon; }
    public double getMaxLat() { return maxLat; }
    public double getVelocidadMaximaMps() { return velocidadMaximaMps; }
}