package trafiqueishon.simulacion;

import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.geo.PieroGNodo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Demanda de un día completo, calculada <b>sin rutas</b>: de dónde sale la gente (zonas
 * residenciales, deducidas de la densidad de calles), a dónde quiere ir (POIs) y a qué hora.
 * La oferta (rutas) se genera después a partir de este resultado. No depende de JavaFX.
 *
 * <p>Los viajes siguen el modelo clásico origen → destino con atracción gravitatoria: la
 * probabilidad de que un viaje a un POI salga de una zona crece con el peso de la zona y decae
 * con la distancia. Son estimaciones razonadas, no aforos medidos.
 */
public final class EberthMDemandaCiudad {

    /** Lado aproximado de una zona residencial. */
    static final double TAMANO_ZONA_METROS = 450;
    /** Una zona necesita al menos tantos nodos viales para contarse como habitada. */
    static final int NODOS_MINIMOS_ZONA = 3;
    /** Distancia a la que la atracción de una zona cae a la cuarta parte. */
    static final double DISTANCIA_REFERENCIA_METROS = 1_500;
    /** Destinos que se recuerdan por zona: con ellos se reparten sus pasajeros. */
    public static final int DESTINOS_POR_ZONA = 10;
    /** Zonas origen que se recuerdan por POI (para que la oferta sepa por dónde pasar). */
    public static final int ORIGENES_POR_DESTINO = 8;
    /** Tope de puntos de gente dibujados sobre el mapa. */
    public static final int MAX_PUNTOS_MAPA = 4_000;
    private static final double METROS_POR_GRADO = 111_320;

    /** Zona residencial: centro, nodos viales que contiene (densidad) y POIs dentro. */
    public record Zona(int id, String nombre, double lon, double lat, double peso) {}

    /** Fila de un ranking: nombre, ubicación y viajes diarios. */
    public record Conteo(String nombre, double lon, double lat, long viajes) {}

    /** Flujo diario desde una zona hacia un POI (para la generación de rutas). */
    public record Flujo(int zona, int poi, double viajes) {}

    private final EberthMParametrosCiudad parametros;
    private final EberthMTipoDia dia;
    private final List<EberthMPoi> pois;
    private final List<Zona> zonas;
    private final double[] porHora = new double[24];
    private final double[] viajesPoi;
    private final double[] viajesZona;
    private final double[][] mejoresViajes;
    private final int[][] mejoresZonas;
    /** Destinos principales de cada zona (viajes y POI), de mayor a menor. */
    private final double[][] destinoViajes;
    private final int[][] destinoPoi;

    private EberthMDemandaCiudad(EberthMParametrosCiudad parametros, EberthMTipoDia dia,
                                 List<EberthMPoi> pois, List<Zona> zonas) {
        this.parametros = parametros;
        this.dia = dia;
        this.pois = pois;
        this.zonas = zonas;
        this.viajesPoi = new double[pois.size()];
        this.viajesZona = new double[zonas.size()];
        this.mejoresViajes = new double[pois.size()][ORIGENES_POR_DESTINO];
        this.mejoresZonas = new int[pois.size()][ORIGENES_POR_DESTINO];
        this.destinoViajes = new double[zonas.size()][DESTINOS_POR_ZONA];
        this.destinoPoi = new int[zonas.size()][DESTINOS_POR_ZONA];
    }

    /**
     * Simula la demanda del día completo.
     *
     * @param grafo red vial (da las zonas residenciales)
     * @param pois  destinos posibles; debe haber al menos uno
     * @throws IllegalArgumentException si no hay POIs o la red no define zonas habitadas
     */
    public static EberthMDemandaCiudad generar(PieroGGrafoVial grafo, List<EberthMPoi> pois,
                                               EberthMParametrosCiudad parametros, EberthMTipoDia dia) {
        if (pois == null || pois.isEmpty()) {
            throw new IllegalArgumentException("Faltan puntos de interés (colegios, hospitales, mercados…).");
        }
        EberthMTipoDia tipoDia = dia == null ? EberthMTipoDia.LABORAL : dia;
        List<Zona> zonas = zonasResidenciales(grafo, pois);
        if (zonas.isEmpty()) throw new IllegalArgumentException("La red vial no define zonas habitadas.");
        EberthMDemandaCiudad d = new EberthMDemandaCiudad(parametros, tipoDia, List.copyOf(pois), zonas);
        d.calcular();
        return d;
    }

    // ------------------------------------------------------------------ zonas

    /** Agrupa los nodos viales en una rejilla; el peso de cada celda es su densidad vial. */
    static List<Zona> zonasResidenciales(PieroGGrafoVial grafo, List<EberthMPoi> pois) {
        double latMedia = (grafo.getMinLat() + grafo.getMaxLat()) / 2;
        double pasoLat = TAMANO_ZONA_METROS / METROS_POR_GRADO;
        double pasoLon = pasoLat / Math.max(0.2, Math.cos(Math.toRadians(latMedia)));
        Map<Long, double[]> celdas = new HashMap<>(); // {nodos, sumaLon, sumaLat}
        for (PieroGNodo n : grafo.getNodos()) {
            double[] c = celdas.computeIfAbsent(clave(n.lon, n.lat, grafo, pasoLon, pasoLat),
                    k -> new double[3]);
            c[0]++;
            c[1] += n.lon;
            c[2] += n.lat;
        }
        // Los POIs "consumen" suelo: una celda con muchos comercios y oficinas es menos residencial.
        Map<Long, Integer> poisPorCelda = new HashMap<>();
        for (EberthMPoi p : pois) {
            poisPorCelda.merge(clave(p.lon(), p.lat(), grafo, pasoLon, pasoLat), 1, Integer::sum);
        }
        List<Zona> zonas = new ArrayList<>();
        for (Map.Entry<Long, double[]> e : celdas.entrySet()) {
            double[] c = e.getValue();
            if (c[0] < NODOS_MINIMOS_ZONA) continue;
            double peso = c[0] / (1 + 0.5 * poisPorCelda.getOrDefault(e.getKey(), 0));
            zonas.add(new Zona(zonas.size(), "", c[1] / c[0], c[2] / c[0], peso));
        }
        zonas.sort(Comparator.comparingDouble(Zona::lat).reversed().thenComparingDouble(Zona::lon));
        List<Zona> nombradas = new ArrayList<>(zonas.size());
        for (int i = 0; i < zonas.size(); i++) {
            Zona z = zonas.get(i);
            nombradas.add(new Zona(i, String.format(Locale.ROOT, "Zona residencial %d (%.4f, %.4f)",
                    i + 1, z.lat(), z.lon()), z.lon(), z.lat(), z.peso()));
        }
        return List.copyOf(nombradas);
    }

    private static long clave(double lon, double lat, PieroGGrafoVial g, double pasoLon, double pasoLat) {
        long col = (long) Math.floor((lon - g.getMinLon()) / pasoLon);
        long fila = (long) Math.floor((lat - g.getMinLat()) / pasoLat);
        return (fila << 32) ^ (col & 0xFFFFFFFFL);
    }

    // ---------------------------------------------------------------- cálculo

    private void calcular() {
        double usuarios = parametros == null ? EberthMParametrosCiudad.POBLACION_POR_DEFECTO
                * EberthMParametrosCiudad.TRANSPORTE_POR_DEFECTO / 100.0
                : parametros.usuariosTransporteDiarios();
        double total = usuarios * dia.demandaGlobal();
        double aplanamiento = dia.aplanamientoPicos();
        for (int h = 0; h < 24; h++) {
            // Sábados y domingos: la curva se mezcla con una plana (picos menos marcados).
            // Con parámetros, las horas punta configuradas (por tipo de día) fijan la curva.
            double f = parametros != null ? parametros.fraccionHoraria(h, dia)
                    : EberthMGeneradorDemanda.fraccionHoraria(h) * aplanamiento
                    + (1.0 / 24) * (1 - aplanamiento);
            porHora[h] = total * f;
            double[] pesos = pesosDestino(h);
            for (int p = 0; p < pois.size(); p++) viajesPoi[p] += porHora[h] * pesos[p];
        }
        // Reparto origen-destino: cada POI recibe viajes de las zonas según peso y cercanía.
        double[] atraccion = new double[zonas.size()];
        for (int p = 0; p < pois.size(); p++) {
            if (viajesPoi[p] <= 0) continue;
            EberthMPoi poi = pois.get(p);
            double suma = 0;
            for (int z = 0; z < atraccion.length; z++) {
                Zona zona = zonas.get(z);
                double d = distanciaMetros(zona.lon(), zona.lat(), poi.lon(), poi.lat())
                        / DISTANCIA_REFERENCIA_METROS;
                atraccion[z] = zona.peso() / ((1 + d) * (1 + d));
                suma += atraccion[z];
            }
            for (int z = 0; z < atraccion.length; z++) {
                double viajes = viajesPoi[p] * atraccion[z] / suma;
                viajesZona[z] += viajes;
                registrarMejor(p, z, viajes);
                registrarDestinoDeZona(z, p, viajes);
            }
        }
    }

    /** Fracción (suma 1) de los viajes de la hora que va a cada POI. */
    private double[] pesosDestino(int hora) {
        double[] sumaPopularidad = new double[EberthMCategoriaPoi.values().length];
        for (EberthMPoi p : pois) sumaPopularidad[p.categoria().ordinal()] += p.popularidad();
        double[] pesos = new double[pois.size()];
        double total = 0;
        for (int i = 0; i < pesos.length; i++) {
            EberthMPoi p = pois.get(i);
            double s = sumaPopularidad[p.categoria().ordinal()];
            pesos[i] = s <= 0 ? 0 : EberthMGeneradorDemanda.pesoCategoria(p.categoria(), hora)
                    * p.popularidad() / s;
            total += pesos[i];
        }
        if (total <= 0) {
            java.util.Arrays.fill(pesos, 1.0 / pesos.length);
        } else {
            for (int i = 0; i < pesos.length; i++) pesos[i] /= total;
        }
        return pesos;
    }

    private void registrarMejor(int poi, int zona, double viajes) {
        double[] v = mejoresViajes[poi];
        int[] zs = mejoresZonas[poi];
        int pos = ORIGENES_POR_DESTINO - 1;
        if (viajes <= v[pos]) return;
        while (pos > 0 && viajes > v[pos - 1]) {
            v[pos] = v[pos - 1];
            zs[pos] = zs[pos - 1];
            pos--;
        }
        v[pos] = viajes;
        zs[pos] = zona;
    }

    private void registrarDestinoDeZona(int zona, int poi, double viajes) {
        double[] v = destinoViajes[zona];
        int[] ps = destinoPoi[zona];
        int pos = DESTINOS_POR_ZONA - 1;
        if (viajes <= v[pos]) return;
        while (pos > 0 && viajes > v[pos - 1]) {
            v[pos] = v[pos - 1];
            ps[pos] = ps[pos - 1];
            pos--;
        }
        v[pos] = viajes;
        ps[pos] = poi;
    }

    private static double distanciaMetros(double lon1, double lat1, double lon2, double lat2) {
        double dx = (lon1 - lon2) * METROS_POR_GRADO * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double dy = (lat1 - lat2) * METROS_POR_GRADO;
        return Math.hypot(dx, dy);
    }

    // ------------------------------------------------------------- resultados

    /** Viajes de cada hora del día (24 valores). */
    public double[] viajesPorHora() {
        return porHora.clone();
    }

    public long totalViajes() {
        double suma = 0;
        for (double v : porHora) suma += v;
        return Math.round(suma);
    }

    public int horaPico() {
        int mejor = 0;
        for (int h = 1; h < 24; h++) if (porHora[h] > porHora[mejor]) mejor = h;
        return mejor;
    }

    public long viajesPico() {
        return Math.round(porHora[horaPico()]);
    }

    public List<Zona> zonas() {
        return zonas;
    }

    public List<EberthMPoi> pois() {
        return pois;
    }

    public EberthMTipoDia tipoDia() {
        return dia;
    }

    /** Id de la zona de la que sale más gente. */
    public int zonaMayorOrigen() {
        int mejor = 0;
        for (int z = 1; z < viajesZona.length; z++) if (viajesZona[z] > viajesZona[mejor]) mejor = z;
        return mejor;
    }

    /** Los {@code n} POIs con más viajes diarios. */
    public List<Conteo> topDestinos(int n) {
        List<Conteo> todos = new ArrayList<>(pois.size());
        for (int p = 0; p < pois.size(); p++) {
            EberthMPoi poi = pois.get(p);
            todos.add(new Conteo(poi.nombre(), poi.lon(), poi.lat(), Math.round(viajesPoi[p])));
        }
        return primeros(todos, n);
    }

    /** Las {@code n} zonas residenciales de donde sale más gente. */
    public List<Conteo> topOrigenes(int n) {
        List<Conteo> todos = new ArrayList<>(zonas.size());
        for (int z = 0; z < zonas.size(); z++) {
            Zona zona = zonas.get(z);
            todos.add(new Conteo(zona.nombre(), zona.lon(), zona.lat(), Math.round(viajesZona[z])));
        }
        return primeros(todos, n);
    }

    private static List<Conteo> primeros(List<Conteo> todos, int n) {
        todos.sort(Comparator.comparingLong(Conteo::viajes).reversed());
        return List.copyOf(todos.subList(0, Math.min(n, todos.size())));
    }

    /** Viajes diarios que llegan al POI {@code poi}. */
    public double viajesHaciaPoi(int poi) {
        return viajesPoi[poi];
    }

    /** Viajes diarios que salen de la zona {@code zona}. */
    public double viajesDesdeZona(int zona) {
        return viajesZona[zona];
    }

    /** Principales flujos zona → POI de un destino, de mayor a menor (base de la oferta). */
    public List<Flujo> flujosHacia(int poi) {
        List<Flujo> flujos = new ArrayList<>();
        for (int i = 0; i < ORIGENES_POR_DESTINO; i++) {
            if (mejoresViajes[poi][i] > 0) flujos.add(new Flujo(mejoresZonas[poi][i], poi, mejoresViajes[poi][i]));
        }
        return flujos;
    }

    /** Destinos de una zona con sus viajes diarios, de mayor a menor (los {@link #DESTINOS_POR_ZONA} principales). */
    public List<Flujo> destinosDesdeZona(int zona) {
        List<Flujo> flujos = new ArrayList<>();
        for (int i = 0; i < DESTINOS_POR_ZONA; i++) {
            if (destinoViajes[zona][i] > 0) flujos.add(new Flujo(zona, destinoPoi[zona][i], destinoViajes[zona][i]));
        }
        return flujos;
    }

    /**
     * Sortea el POI destino de un pasajero de la zona según el flujo real zona → POI: si el 60 %
     * de la zona va al colegio, el 60 % de sus pasajeros recibe el colegio. -1 si la zona no tiene flujos.
     */
    public int sortearPoi(int zona, Random azar) {
        double total = 0;
        for (double v : destinoViajes[zona]) total += v;
        if (total <= 0) return -1;
        double r = azar.nextDouble() * total;
        for (int i = 0; i < DESTINOS_POR_ZONA; i++) {
            r -= destinoViajes[zona][i];
            if (r < 0) return destinoPoi[zona][i];
        }
        return destinoPoi[zona][0];
    }

    /** «Se generaron 10,000 pasajeros en la zona A. 6,000 van al Colegio X, 3,000 al Hospital Y…». */
    public String resumenZona(int zona) {
        List<Flujo> flujos = destinosDesdeZona(zona);
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "Se generaron %,d pasajeros en %s.", Math.round(viajesZona[zona]), zonas.get(zona).nombre()));
        for (int i = 0; i < Math.min(3, flujos.size()); i++) {
            sb.append(i == 0 ? " " : ", ").append(String.format(Locale.ROOT, "%,d %s %s",
                    Math.round(flujos.get(i).viajes()), i == 0 ? "van a" : "a",
                    pois.get(flujos.get(i).poi()).nombre()));
        }
        return sb.append('.').toString();
    }

    /**
     * Gente esperando a esa hora como {@code lon, lat} por punto, repartida en las zonas según
     * de dónde sale la demanda. Cada punto representa varias personas; es determinista.
     */
    public double[] puntosGente(int hora) {
        int h = Math.floorMod(hora, 24);
        int puntos = (int) Math.min(MAX_PUNTOS_MAPA, Math.max(1, Math.round(porHora[h] / 10)));
        double[] acumulado = new double[zonas.size()];
        double suma = 0;
        for (int z = 0; z < acumulado.length; z++) {
            suma += viajesZona[z];
            acumulado[z] = suma;
        }
        double total = suma;
        double pasoLat = TAMANO_ZONA_METROS / METROS_POR_GRADO;
        Random azar = new Random(31L * h + zonas.size());
        double[] salida = new double[puntos * 2];
        for (int i = 0; i < puntos; i++) {
            double r = azar.nextDouble() * total;
            int z = java.util.Arrays.binarySearch(acumulado, r);
            if (z < 0) z = -z - 1;
            z = Math.min(z, acumulado.length - 1);
            Zona zona = zonas.get(z);
            double pasoLon = pasoLat / Math.max(0.2, Math.cos(Math.toRadians(zona.lat())));
            salida[2 * i] = zona.lon() + (azar.nextDouble() - 0.5) * pasoLon;
            salida[2 * i + 1] = zona.lat() + (azar.nextDouble() - 0.5) * pasoLat;
        }
        return salida;
    }

    /** Texto de síntesis para el panel de resultados. */
    public String resumen(String ciudad) {
        List<Conteo> destinos = topDestinos(1);
        String principal = destinos.isEmpty() ? "—" : destinos.get(0).nombre();
        return String.format(Locale.ROOT,
                "%s generará %,d viajes diarios (%s). El pico máximo es de %,d pasajeros a las %02d:00, "
                        + "con destino principal a %s.",
                ciudad, totalViajes(), dia, viajesPico(), horaPico(), principal);
    }
}
