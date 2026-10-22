package trafiqueishon.simulacion;

import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.geo.PieroGNodo;
import trafiqueishon.geo.PieroGPathfinder;
import trafiqueishon.geo.PieroGRutaResult;
import trafiqueishon.ruta.LuisYCalculadorTrayecto;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.ruta.LuisYPuntoImportante.TipoPunto;
import trafiqueishon.ruta.LuisYTrayecto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Autogenera rutas de micro para cubrir la demanda: une POIs importantes (hospitales,
 * mercados, universidades…) separados por una distancia útil, traza el camino con A*,
 * agrega 2-3 paradas intermedias cercanas a ese camino y deriva demanda, frecuencia y unidades
 * de los POIs que conecta y de la población de la ciudad. No depende de JavaFX; puede tardar segundos en mapas grandes, por lo que
 * debe llamarse fuera del hilo de la interfaz.
 */
public final class EberthMGeneradorRutas {

    private static final Logger LOG = Logger.getLogger(EberthMGeneradorRutas.class.getName());

    public static final int MIN_RUTAS = 3;
    public static final int MAX_RUTAS = 5;
    /** Distancia mínima en línea recta entre origen y destino para que la ruta sea útil. */
    public static final double DISTANCIA_MINIMA_METROS = 3_000;
    public static final String PREFIJO_NOMBRE = "Ruta Auto ";

    /** Pasajeros por hora que aporta cada punto de prioridad de un POI de la ruta (ciudad de 100 000 hab.). */
    static final int DEMANDA_POR_PRIORIDAD = 10;
    /** Frecuencia (min) cuando no hay parámetros de ciudad. */
    static final int FRECUENCIA_NORMAL_MIN = 10;
    /** Duración típica de una vuelta completa (min) cuando aún no hay un trazado concreto. */
    static final double VUELTA_TIPICA_MIN = 60;
    /** Parada de pasajeros: segundos de detención por parada (igual que el simulador). */
    private static final double SEGUNDOS_POR_PARADA = 15;
    /** Tope de micros simultáneos en pantalla, para no saturar la RAM. */
    public static final int FLOTA_MAXIMA_PANTALLA = 500;
    /** Micros aún disponibles bajo {@link #FLOTA_MAXIMA_PANTALLA} durante una generación. */
    private int flotaRestante;
    /** Pasajeros que carga una micro por viaje (ver {@link EberthMDemandaPasajeros#ABORDAJE_MAXIMO}). */
    static final int PASAJEROS_POR_MICRO = EberthMDemandaPasajeros.ABORDAJE_MAXIMO;
    static final int MIN_UNIDADES = 2;
    /** Cuántas rutas se reparten la demanda de la ciudad en la generación en curso. */
    private int rutasEnCiudad = 1;
    /** Rutas que faltan por generar en la generación en curso (reparte el tope de flota). */
    private int rutasPendientes = 1;
    private static final long POBLACION_REFERENCIA = 100_000;

    private static final int MAX_CANDIDATOS = 40;
    private static final int INTENTOS_POR_RUTA = 40;
    private static final int MAX_PARADAS_INTERMEDIAS = 3;
    private static final double SEPARACION_PARADAS_METROS = 700;
    /** Fracción máxima de tramos que una ruta nueva puede compartir con otra ya existente. */
    private static final double SOLAPE_MAXIMO = 0.6;
    private static final double METROS_POR_GRADO = 111_320;

    private final PieroGGrafoVial red;
    private final List<LuisYPuntoImportante> candidatos;
    private final Map<EberthMPoi, LuisYPuntoImportante> puntoDePoi = new IdentityHashMap<>();
    /** Todos los puntos cargados, por nombre y coordenadas, para enlazar con los POIs de la demanda. */
    private final Map<String, LuisYPuntoImportante> puntoPorPoi = new java.util.HashMap<>();
    private final EberthMIndicePoi indicePoi;
    private final PieroGPathfinder pathfinder;
    private final Random azar;
    private final double distanciaMinima;
    private final double multiplicadorPoblacion;
    private final EberthMParametrosCiudad ciudad;

    public EberthMGeneradorRutas(PieroGGrafoVial red, List<LuisYPuntoImportante> puntos,
                                 EberthMParametrosCiudad ciudad) {
        this(red, puntos, ciudad, new Random());
    }

    /** @param ciudad parámetros de la ciudad; {@code null} usa el multiplicador neutro (1,0) */
    public EberthMGeneradorRutas(PieroGGrafoVial red, List<LuisYPuntoImportante> puntos,
                                 EberthMParametrosCiudad ciudad, Random azar) {
        this.red = red;
        this.azar = azar;
        this.ciudad = ciudad;
        this.multiplicadorPoblacion = ciudad == null ? 1.0
                : multiplicadorPoblacion(ciudad.getPoblacionTotal());
        for (LuisYPuntoImportante p : puntos) puntoPorPoi.putIfAbsent(p.nombre() + "|" + p.lon() + "|" + p.lat(), p);
        this.pathfinder = new PieroGPathfinder(red);
        this.pathfinder.setModo(PieroGPathfinder.Modo.A_ESTRELLA);

        List<LuisYPuntoImportante> relevantes = new ArrayList<>(puntos.stream()
                .filter(p -> prioridad(p.tipo()) > 0).toList());
        // Sin POIs reconocibles (solo paraderos genéricos) se usan todos los puntos con igual peso.
        boolean generico = relevantes.size() < 2;
        List<LuisYPuntoImportante> base = generico ? new ArrayList<>(puntos) : relevantes;

        List<EberthMPoi> pois = new ArrayList<>(base.size());
        for (LuisYPuntoImportante p : base) {
            EberthMPoi poi = new EberthMPoi(p.nombre(), p.lon(), p.lat(),
                    EberthMCategoriaPoi.desde(p.tipo()));
            puntoDePoi.put(poi, p);
            pois.add(poi);
        }
        this.indicePoi = new EberthMIndicePoi(pois);
        base.sort(Comparator.comparingInt((LuisYPuntoImportante p) -> peso(p)).reversed());
        this.candidatos = List.copyOf(base.subList(0, Math.min(MAX_CANDIDATOS, base.size())));
        this.distanciaMinima = distanciaMinimaEfectiva(candidatos);
    }

    /** Peso extra de un punto a ≤ {@link #RADIO_ZONA_DESATENDIDA_METROS} de una zona sin cobertura. */
    static final int BONO_ZONA_DESATENDIDA = 8;
    static final double RADIO_ZONA_DESATENDIDA_METROS = 400;
    private double[] zonasDesatendidas = new double[0];

    /**
     * Zonas donde la simulación detectó demanda sin parada de ruta a ≤ 300 m, como
     * {@code lon, lat, pasajeros} por zona. Los POIs cercanos a ellas pesan más al elegir los
     * extremos de las rutas nuevas, de modo que la generación tienda a cubrir esa demanda.
     */
    public void priorizarZonasSinCobertura(double[] lonLatPasajeros) {
        this.zonasDesatendidas = lonLatPasajeros == null ? new double[0] : lonLatPasajeros.clone();
    }

    private int pesoConZonas(LuisYPuntoImportante p) {
        int peso = peso(p);
        for (int i = 0; i + 2 < zonasDesatendidas.length; i += 3) {
            double dx = (p.lon() - zonasDesatendidas[i]) * METROS_POR_GRADO
                    * Math.cos(Math.toRadians(p.lat()));
            double dy = (p.lat() - zonasDesatendidas[i + 1]) * METROS_POR_GRADO;
            if (Math.hypot(dx, dy) <= RADIO_ZONA_DESATENDIDA_METROS) return peso + BONO_ZONA_DESATENDIDA;
        }
        return peso;
    }

    /** Genera entre {@value #MIN_RUTAS} y {@value #MAX_RUTAS} rutas (menos si el mapa no da más). */
    public List<EberthMServicioMicro> generar(List<EberthMServicioMicro> existentes) {
        return generar(existentes, MIN_RUTAS + azar.nextInt(MAX_RUTAS - MIN_RUTAS + 1));
    }

    /** Valores elegidos por el usuario: cantidad de rutas, paradas y unidades por ruta, frecuencias normal y de hora punta. */
    /** @param unidades micros por ruta; {@code 0} = calcularlas con {@link #unidadesParaRuta} */
    public record Opciones(int rutas, int paradas, int unidades, int frecuenciaMin,
                           int frecuenciaPuntaMin, double coberturaObjetivo, TiposRuta tipos) {
        /** Ambos tipos de ruta. */
        public Opciones(int rutas, int paradas, int unidades, int frecuenciaMin, int frecuenciaPuntaMin,
                        double coberturaObjetivo) {
            this(rutas, paradas, unidades, frecuenciaMin, frecuenciaPuntaMin, coberturaObjetivo, TiposRuta.AMBAS);
        }

        /** Sin ampliar: se generan exactamente {@code rutas} rutas. */
        public Opciones(int rutas, int paradas, int unidades, int frecuenciaMin, int frecuenciaPuntaMin) {
            this(rutas, paradas, unidades, frecuenciaMin, frecuenciaPuntaMin, 0);
        }

        /** Mismas opciones con otra cantidad de rutas y sin ampliar. */
        public Opciones conRutas(int nuevas) {
            return new Opciones(nuevas, paradas, unidades, frecuenciaMin, frecuenciaPuntaMin, 0, tipos);
        }

        public Opciones {
            if (rutas < 1 || paradas < 2 || unidades < 0 || frecuenciaMin < 1
                    || frecuenciaPuntaMin < 1 || frecuenciaPuntaMin > frecuenciaMin) {
                throw new IllegalArgumentException("Opciones de generación no válidas");
            }
            if (tipos == null) tipos = TiposRuta.AMBAS;
        }
    }

    public List<EberthMServicioMicro> generar(List<EberthMServicioMicro> existentes, int cantidad) {
        return generar(existentes, cantidad, null);
    }

    public List<EberthMServicioMicro> generar(List<EberthMServicioMicro> existentes, Opciones opciones) {
        return generar(existentes, opciones.rutas(), opciones);
    }

    private List<EberthMServicioMicro> generar(List<EberthMServicioMicro> existentes, int cantidad,
                                               Opciones opciones) {
        List<EberthMServicioMicro> generadas = new ArrayList<>();
        if (candidatos.size() < 2) return generadas;

        List<EberthMServicioMicro> todas = new ArrayList<>(existentes);
        List<Set<PieroGEdge>> trazados = new ArrayList<>();
        for (EberthMServicioMicro s : existentes) trazados.add(identidad(s.trayecto().edges()));
        Set<String> parejasUsadas = new HashSet<>();
        int numero = siguienteNumero(existentes);
        flotaRestante = FLOTA_MAXIMA_PANTALLA
                - existentes.stream().mapToInt(EberthMServicioMicro::unidadesDisponibles).sum();
        rutasEnCiudad = Math.max(1, existentes.size() + cantidad);

        for (int n = 0; n < cantidad && flotaRestante > 0; n++) {
            for (int intento = 0; intento < INTENTOS_POR_RUTA; intento++) {
                EberthMServicioMicro ruta = intentarRuta(numero, todas, trazados, parejasUsadas, opciones);
                if (ruta == null) continue;
                generadas.add(ruta);
                todas.add(ruta);
                trazados.add(identidad(ruta.trayecto().edges()));
                numero++;
                break;
            }
        }
        return generadas;
    }

    private EberthMServicioMicro intentarRuta(int numero, List<EberthMServicioMicro> todas,
            List<Set<PieroGEdge>> trazados, Set<String> parejasUsadas, Opciones opciones) {
        LuisYPuntoImportante origen = elegirPonderado(candidatos, null);
        List<LuisYPuntoImportante> lejanos = candidatos.stream()
                .filter(p -> p != origen && metros(origen, p) >= distanciaMinima).toList();
        if (lejanos.isEmpty()) return null;
        LuisYPuntoImportante destino = elegirPonderado(lejanos, origen);
        String pareja = origen.id().compareTo(destino.id()) < 0
                ? origen.id() + "|" + destino.id() : destino.id() + "|" + origen.id();
        if (!parejasUsadas.add(pareja)) return null;

        PieroGNodo nodoOrigen = red.nodoMasCercanoLonLat(origen.lon(), origen.lat());
        PieroGNodo nodoDestino = red.nodoMasCercanoLonLat(destino.lon(), destino.lat());
        if (nodoOrigen == null || nodoDestino == null || nodoOrigen == nodoDestino) return null;
        PieroGRutaResult traza = pathfinder.ruta(nodoOrigen, nodoDestino);
        if (traza == null || traza.esVacia()) return null;
        if (solape(traza.getEdges(), trazados) > SOLAPE_MAXIMO) return null;

        List<LuisYPuntoImportante> paraderos = new ArrayList<>();
        paraderos.add(origen);
        paraderos.addAll(paradasIntermedias(traza.getEdges(), origen, destino,
                opciones == null ? MAX_PARADAS_INTERMEDIAS : Math.max(0, opciones.paradas() - 2)));
        paraderos.add(destino);

        int sumaPrioridades = paraderos.stream().mapToInt(EberthMGeneradorRutas::peso).sum();
        int demanda = demandaBase(sumaPrioridades, multiplicadorPoblacion);
        return construirServicio(numero, todas, paraderos, traza, demanda, opciones);
    }

    /** Frecuencia, flota y servicio de una ruta ya trazada; null si el servicio no se puede construir. */
    private EberthMServicioMicro construirServicio(int numero, List<EberthMServicioMicro> todas,
            List<LuisYPuntoImportante> paraderos, PieroGRutaResult traza, int demanda, Opciones opciones) {
        LuisYPuntoImportante origen = paraderos.getFirst();
        LuisYPuntoImportante destino = paraderos.getLast();
        int frecuencia = opciones == null ? frecuenciaPorCiudad(ciudad) : opciones.frecuenciaMin();
        int frecuenciaPunta = opciones == null
                ? EberthMServicioMicro.frecuenciaPuntaPorDefecto(frecuencia)
                : opciones.frecuenciaPuntaMin();
        int unidades;
        if (opciones != null && opciones.unidades() > 0) {
            unidades = opciones.unidades();
        } else {
            double vuelta = vueltaCompletaMinutos(traza.tiempoSegundos(), frecuencia,
                    paraderos.size());
            unidades = unidadesPorRuta(ciudad, rutasEnCiudad, vuelta, frecuenciaPunta);
        }
        unidades = Math.max(1, Math.min(unidades, flotaRestante));
        try {
            EberthMServicioMicro servicio = EberthMGestorServicios.construirServicio(red, "micro-auto-" + java.util.UUID.randomUUID(),
                    PREFIJO_NOMBRE + numero, paraderos, false, EberthMTipoRetorno.INVERSO, List.of(),
                    frecuencia, frecuenciaPunta, unidades, demanda, colorAleatorio(todas));
            flotaRestante -= unidades;
            return servicio;
        } catch (RuntimeException ex) {
            LOG.log(Level.FINE, "Ruta autogenerada descartada: " + origen.nombre()
                    + " → " + destino.nombre(), ex);
            return null;
        }
    }

    // ------------------------------------------------------------------
    //  RED DE RUTAS A PARTIR DE LA DEMANDA (troncales + alimentadoras)
    // ------------------------------------------------------------------

    /** Troncal: de un extremo de la ciudad al otro. Alimentadora: de la periferia a un POI central. */
    public enum TipoRuta { TRONCAL, ALIMENTADORA }

    /** Qué tipos de ruta se generan; con {@link #AMBAS} el 80 % son troncales y el 20 % alimentadoras. */
    public enum TiposRuta {
        TRONCALES, ALIMENTADORAS, AMBAS;

        /** Tipo de la ruta número {@code indice} (desde 0): en cada bloque de 5, 4 troncales y 1 alimentadora. */
        public TipoRuta deLaRuta(int indice) {
            return switch (this) {
                case TRONCALES -> TipoRuta.TRONCAL;
                case ALIMENTADORAS -> TipoRuta.ALIMENTADORA;
                case AMBAS -> indice % 5 == 4 ? TipoRuta.ALIMENTADORA : TipoRuta.TRONCAL;
            };
        }
    }

    /**
     * Longitudes admitidas (metros por el trazado real). Nunca se acepta una ruta bajo
     * {@code absolutaMin}: una ruta más corta se descarta y se busca otra más larga.
     */
    public record Longitudes(double troncalMin, double troncalMax, double alimentadoraMin,
                             double alimentadoraMax, double absolutaMin) {
        public static final Longitudes REALES = new Longitudes(8_000, 12_000, 4_000, 6_500, 4_000);

        /** Mismas proporciones en una ciudad más pequeña (para pruebas con mapas diminutos). */
        public Longitudes escalada(double factor) {
            return new Longitudes(troncalMin * factor, troncalMax * factor, alimentadoraMin * factor,
                    alimentadoraMax * factor, absolutaMin * factor);
        }
    }

    /** Cobertura de la demanda que se busca por defecto al ampliar la cantidad de rutas. */
    public static final double COBERTURA_OBJETIVO = 0.8;
    /** Tope de rutas nuevas que se planifican para estimar la cobertura. */
    public static final int MAX_RUTAS_PLAN = 50;
    /** Rutas adicionales máximas sobre las pedidas para llegar a {@link #COBERTURA_OBJETIVO}. */
    static final int MAX_RUTAS_EXTRA = 15;
    /** POIs populares que debe tocar una ruta (contando el destino de una alimentadora). */
    public static final int POIS_MINIMOS_RUTA = 3;
    private static final int POIS_OBJETIVO_TRONCAL = 4;
    private static final int POIS_OBJETIVO_ALIMENTADORA = 3;
    /** El trazado por calles mide ≈ 1,3 veces la línea que une sus paradas; se afina con cada trazado. */
    private static final double FACTOR_RODEO = 1.3;
    private static final double ANCHO_CORREDOR_METROS = 900;
    private static final double SEPARACION_POIS_METROS = 300;
    private static final int PAREJAS_A_MUESTREAR = 30;
    private static final int TRAZADOS_POR_NIVEL = 6;
    /** Una zona queda atendida si hay una parada a esta distancia (el simulador admite 300 m de caminata). */
    static final double RADIO_PARADA_ZONA_METROS = 250;
    /** Tope de paradas por ruta (POIs, zonas de demanda y extremos). */
    static final int MAX_PARADAS_RUTA = 45;
    /** Niveles de exigencia: {POIs mínimos, % de la longitud mínima}. Siempre ≥ 1 POI y ≥ longitud absoluta. */
    private static final int[][] NIVELES = {{POIS_MINIMOS_RUTA, 100}, {2, 100}, {2, 85}, {1, 85}};

    private Longitudes longitudes = Longitudes.REALES;
    /** Cuánto más largo que la línea recta resulta el trazado por calles (aprendido de los trazados hechos). */
    private double rodeo = FACTOR_RODEO;

    public void setLongitudes(Longitudes longitudes) {
        this.longitudes = longitudes;
    }

    private java.util.function.BooleanSupplier cancelado = () -> false;

    /** Permite abandonar una generación larga (p. ej. un plan que ya no interesa) entre rutas. */
    public void setCancelacion(java.util.function.BooleanSupplier cancelado) {
        this.cancelado = cancelado;
    }

    /**
     * Crea la red de rutas que sirve la demanda ya generada. Las troncales unen dos extremos
     * opuestos de la ciudad (≥ 8 km) pasando por los POIs más populares; las alimentadoras unen la
     * periferia con un POI central (4-6 km). Cada ruta debe tocar POIs populares y no bajar de 4 km:
     * si no, se descarta y se busca otra más larga. Las zonas ya cubiertas pesan cada vez menos,
     * así que cada ruta nueva ataca la demanda que aún no tiene servicio.
     */
    public List<EberthMServicioMicro> generarDesdeDemanda(EberthMDemandaCiudad demanda,
            List<EberthMServicioMicro> existentes, Opciones opciones) {
        List<EberthMServicioMicro> generadas = new ArrayList<>();
        Escenario esc = new Escenario(demanda);
        List<EberthMServicioMicro> todas = new ArrayList<>(existentes);
        for (EberthMServicioMicro s : existentes) esc.aplicar(EberthMCoberturaDemanda.alcance(demanda, s.trayecto().edges()));
        int numero = siguienteNumero(existentes);
        flotaRestante = FLOTA_MAXIMA_PANTALLA
                - existentes.stream().mapToInt(EberthMServicioMicro::unidadesDisponibles).sum();
        rutasEnCiudad = Math.max(1, existentes.size() + opciones.rutas());

        int indice = 0;
        int fallosSeguidos = 0;
        int extras = 0;
        while (flotaRestante > 0 && fallosSeguidos < 2 && !cancelado.getAsBoolean()) {
            boolean principales = generadas.size() < opciones.rutas();
            // Con «cubrir objetivo», se añaden las rutas que falten hasta alcanzar la cobertura deseada.
            if (!principales && (opciones.coberturaObjetivo() <= 0 || generadas.isEmpty()
                    || extras >= MAX_RUTAS_EXTRA || esc.cobertura() >= opciones.coberturaObjetivo())) break;
            rutasPendientes = Math.max(1, opciones.rutas() - generadas.size());
            TipoRuta tipo = opciones.tipos().deLaRuta(indice++);
            EberthMServicioMicro ruta = esc.crearRuta(tipo, numero, todas, opciones);
            // Con ambos tipos, si el preferido no cabe en este mapa se prueba el otro.
            if (ruta == null && opciones.tipos() == TiposRuta.AMBAS) {
                ruta = esc.crearRuta(tipo == TipoRuta.TRONCAL ? TipoRuta.ALIMENTADORA : TipoRuta.TRONCAL,
                        numero, todas, opciones);
            }
            if (ruta == null) {
                fallosSeguidos++;
                continue;
            }
            fallosSeguidos = 0;
            generadas.add(ruta);
            todas.add(ruta);
            numero++;
            if (!principales) extras++;
        }
        return generadas;
    }

    /**
     * Plan de cobertura: las rutas que se generarían y el porcentaje de demanda cubierto tras cada
     * una. {@code cobertura[0]} es la de las rutas ya existentes; {@code cobertura[k]}, tras añadir
     * las primeras {@code k} rutas nuevas.
     */
    public record Plan(List<EberthMServicioMicro> rutas, double[] cobertura) {
        public double coberturaCon(int nuevas) {
            return cobertura[Math.clamp(nuevas, 0, cobertura.length - 1)];
        }

        /** Menos rutas nuevas que alcanzan el objetivo, o {@code -1} si ni todas bastan. */
        public int rutasParaObjetivo(double objetivo) {
            for (int k = 0; k < cobertura.length; k++) if (cobertura[k] >= objetivo) return k;
            return -1;
        }

        public double coberturaMaxima() {
            return cobertura[cobertura.length - 1];
        }

        /** Cuántas de las primeras {@code nuevas} rutas son del {@code tipo} indicado. */
        public int cuantas(int nuevas, TipoRuta tipo, TiposRuta tipos) {
            int n = 0;
            for (int i = 0; i < Math.min(nuevas, rutas.size()); i++) if (tipos.deLaRuta(i) == tipo) n++;
            return n;
        }
    }

    /** Planifica hasta {@code maxRutas} rutas nuevas sin aplicarlas, para estimar la cobertura de cada cantidad. */
    public Plan planificar(EberthMDemandaCiudad demanda, List<EberthMServicioMicro> existentes,
                           Opciones opciones, int maxRutas) {
        List<EberthMServicioMicro> rutas = generarDesdeDemanda(demanda, existentes, opciones.conRutas(maxRutas));
        return new Plan(rutas, EberthMCoberturaDemanda.coberturaAcumulada(demanda, existentes, rutas));
    }

    /** Una candidata antes de construirse: extremos, línea recta entre ellos y demanda sin cubrir que toca. */
    private record Candidato(double ax, double ay, double bx, double by, LuisYPuntoImportante origen,
                             LuisYPuntoImportante destino, double recta, double ganancia) {}

    /**
     * Demanda de la ciudad proyectada a metros locales, con lo ya cubierto por las rutas creadas.
     * Un viaje zona → POI queda cubierto como en {@link EberthMCoberturaDemanda}.
     */
    private final class Escenario {
        private final EberthMDemandaCiudad demanda;
        private final double lon0, lat0, kx;
        private final int nz, np;
        private final double[] zx, zy, zr;
        private final List<List<EberthMDemandaCiudad.Flujo>> flujos = new ArrayList<>();
        private final boolean[][] cubierto;
        /** Zonas de origen ya junto a una ruta (≤ 450 m): en el simulador su gente sale a esperar el micro. */
        private final boolean[] zonaServida;
        private final double[] px, py;
        /** Puntaje de popularidad de cada POI; 0 si no es uno de los puntos cargados. */
        private final double[] puntaje;
        private final boolean[] popular;
        private final double radio;
        private final double totalFlujos;
        private double cubiertoFlujos;
        private final List<Integer> centrales = new ArrayList<>();

        Escenario(EberthMDemandaCiudad demanda) {
            this.demanda = demanda;
            nz = demanda.zonas().size();
            np = demanda.pois().size();
            double sw = 0, sx = 0, sy = 0;
            for (EberthMDemandaCiudad.Zona z : demanda.zonas()) {
                double w = demanda.viajesDesdeZona(z.id());
                sw += w;
                sx += w * z.lon();
                sy += w * z.lat();
            }
            lon0 = sw > 0 ? sx / sw : demanda.zonas().getFirst().lon();
            lat0 = sw > 0 ? sy / sw : demanda.zonas().getFirst().lat();
            kx = METROS_POR_GRADO * Math.cos(Math.toRadians(lat0));
            zx = new double[nz];
            zy = new double[nz];
            zr = new double[nz];
            cubierto = new boolean[nz][];
            zonaServida = new boolean[nz];
            double total = 0;
            for (EberthMDemandaCiudad.Zona z : demanda.zonas()) {
                zx[z.id()] = (z.lon() - lon0) * kx;
                zy[z.id()] = (z.lat() - lat0) * METROS_POR_GRADO;
                zr[z.id()] = Math.hypot(zx[z.id()], zy[z.id()]);
                List<EberthMDemandaCiudad.Flujo> fl = demanda.destinosDesdeZona(z.id());
                flujos.add(fl);
                cubierto[z.id()] = new boolean[fl.size()];
                for (EberthMDemandaCiudad.Flujo f : fl) total += f.viajes();
            }
            totalFlujos = total;
            px = new double[np];
            py = new double[np];
            puntaje = new double[np];
            popular = new boolean[np];
            List<Double> puntajes = new ArrayList<>();
            for (int p = 0; p < np; p++) {
                EberthMPoi poi = demanda.pois().get(p);
                px[p] = (poi.lon() - lon0) * kx;
                py[p] = (poi.lat() - lat0) * METROS_POR_GRADO;
                LuisYPuntoImportante punto = puntoPorPoi.get(clavePoi(poi));
                if (punto == null) continue;
                boolean destacado = punto.tipo() == TipoPunto.SALUD || punto.tipo() == TipoPunto.MERCADO;
                puntaje[p] = Math.max(1e-6, demanda.viajesHaciaPoi(p)) * (destacado ? 2 : 1);
                puntajes.add(puntaje[p]);
                popular[p] = destacado;
            }
            // Populares: mercados y hospitales, y el 30 % de los POIs más concurridos.
            Collections.sort(puntajes);
            double umbral = puntajes.isEmpty() ? 0 : puntajes.get((int) (puntajes.size() * 0.7));
            for (int p = 0; p < np; p++) if (puntaje[p] > 0 && puntaje[p] >= umbral) popular[p] = true;
            radio = Math.max(1_500, radioPonderado(0.85));
            for (int p = 0; p < np; p++) {
                if (popular[p] && Math.hypot(px[p], py[p]) <= radio * 0.5) centrales.add(p);
            }
            if (centrales.isEmpty()) {
                for (int p = 0; p < np; p++) if (popular[p]) centrales.add(p);
                centrales.sort(Comparator.comparingDouble(p -> Math.hypot(px[p], py[p])));
            } else {
                centrales.sort(Comparator.comparingDouble((Integer p) -> puntaje[p]).reversed());
            }
        }

        /** Distancia al centro de la demanda bajo la cual vive la fracción {@code q} de los viajes. */
        private double radioPonderado(double q) {
            Integer[] orden = new Integer[nz];
            for (int z = 0; z < nz; z++) orden[z] = z;
            java.util.Arrays.sort(orden, Comparator.comparingDouble(z -> zr[z]));
            double suma = 0, total = 0;
            for (int z = 0; z < nz; z++) total += demanda.viajesDesdeZona(z);
            for (int z : orden) {
                suma += demanda.viajesDesdeZona(z);
                if (suma >= q * total) return zr[z];
            }
            return zr[orden[nz - 1]];
        }

        double cobertura() {
            return totalFlujos <= 0 ? 0 : cubiertoFlujos / totalFlujos;
        }

        /** Marca como cubiertos los viajes que sirve un trazado (su {@code alcance}). */
        void aplicar(boolean[][] alcance) {
            for (int z = 0; z < nz; z++) {
                if (!alcance[0][z]) continue;
                zonaServida[z] = true;
                List<EberthMDemandaCiudad.Flujo> fl = flujos.get(z);
                for (int i = 0; i < fl.size(); i++) {
                    if (!cubierto[z][i] && alcance[1][fl.get(i).poi()]) {
                        cubierto[z][i] = true;
                        cubiertoFlujos += fl.get(i).viajes();
                    }
                }
            }
        }

        /** Viajes diarios que sirve un trazado: {nuevos, ya cubiertos por otras rutas}. */
        double[] impacto(boolean[][] alcance) {
            double nuevos = 0, repetidos = 0;
            for (int z = 0; z < nz; z++) {
                if (!alcance[0][z]) continue;
                List<EberthMDemandaCiudad.Flujo> fl = flujos.get(z);
                for (int i = 0; i < fl.size(); i++) {
                    if (!alcance[1][fl.get(i).poi()]) continue;
                    if (cubierto[z][i]) repetidos += fl.get(i).viajes();
                    else nuevos += fl.get(i).viajes();
                }
            }
            return new double[]{nuevos, repetidos};
        }

        /** Viajes sin cubrir que salen de la zona. */
        private double necesidad(int z) {
            double suma = 0;
            List<EberthMDemandaCiudad.Flujo> fl = flujos.get(z);
            for (int i = 0; i < fl.size(); i++) if (!cubierto[z][i]) suma += fl.get(i).viajes();
            return suma;
        }

        /** Hasta {@code n} zonas sin repetir, sorteadas con probabilidad proporcional a su necesidad. */
        private List<Integer> muestrear(double radioMin, int n) {
            List<double[]> claves = new ArrayList<>();
            for (int z = 0; z < nz; z++) {
                double w = necesidad(z) + (zonaServida[z] ? 0 : demanda.viajesDesdeZona(z));
                if (zr[z] < radioMin || w <= 0) continue;
                claves.add(new double[]{-Math.log(1 - azar.nextDouble()) / w, z});
            }
            claves.sort(Comparator.comparingDouble(c -> c[0]));
            List<Integer> elegidas = new ArrayList<>();
            for (int i = 0; i < Math.min(n, claves.size()); i++) elegidas.add((int) claves.get(i)[1]);
            return elegidas;
        }

        /** Demanda sin cubrir que queda junto a la línea recta a→b (zonas a ≤ 450 m, POIs a ≤ 400 m), más la de las zonas sin ruta. */
        double ganancia(double ax, double ay, double bx, double by) {
            boolean[] poiCerca = new boolean[np];
            for (int p = 0; p < np; p++) {
                poiCerca[p] = puntaje[p] > 0 && distSegmento(px[p], py[p], ax, ay, bx, by)
                        <= EberthMCoberturaDemanda.RADIO_POI_METROS;
            }
            double suma = 0;
            for (int z = 0; z < nz; z++) {
                if (distSegmento(zx[z], zy[z], ax, ay, bx, by) > EberthMCoberturaDemanda.RADIO_ZONA_METROS) continue;
                // Una zona sin ruta aporta toda su gente: la ruta nueva debe pasar por sus orígenes.
                if (!zonaServida[z]) suma += demanda.viajesDesdeZona(z);
                List<EberthMDemandaCiudad.Flujo> fl = flujos.get(z);
                for (int i = 0; i < fl.size(); i++) {
                    if (!cubierto[z][i] && poiCerca[fl.get(i).poi()]) suma += fl.get(i).viajes();
                }
            }
            return suma;
        }

        /** Candidatas de mayor a menor demanda sin cubrir, con extremos a {@code recta} entre mín. y máx. */
        List<Candidato> candidatos(TipoRuta tipo, double rectaMin, double rectaMax) {
            List<Candidato> lista = new ArrayList<>();
            if (tipo == TipoRuta.TRONCAL) {
                List<Integer> extremos = muestrear(radio * 0.4, PAREJAS_A_MUESTREAR);
                // Extremos opuestos (≥ 110° entre sí); si el mapa no los ofrece, basta que estén lejos.
                for (boolean opuestos : new boolean[]{true, false}) {
                    for (int i = 0; i < extremos.size(); i++) {
                        for (int j = i + 1; j < extremos.size(); j++) {
                            int a = extremos.get(i), b = extremos.get(j);
                            double recta = Math.hypot(zx[a] - zx[b], zy[a] - zy[b]);
                            if (recta < rectaMin || recta > rectaMax) continue;
                            double coseno = (zx[a] * zx[b] + zy[a] * zy[b]) / Math.max(1, zr[a] * zr[b]);
                            if (opuestos && coseno > -0.34) continue;
                            lista.add(new Candidato(zx[a], zy[a], zx[b], zy[b], puntoZona(a), puntoZona(b),
                                    recta, ganancia(zx[a], zy[a], zx[b], zy[b])));
                        }
                    }
                    if (!lista.isEmpty()) break;
                }
            } else {
                List<Integer> origenes = muestrear(radio * 0.3, PAREJAS_A_MUESTREAR);
                for (int a : origenes) {
                    for (int p : centrales.subList(0, Math.min(10, centrales.size()))) {
                        double recta = Math.hypot(zx[a] - px[p], zy[a] - py[p]);
                        if (recta < rectaMin || recta > rectaMax) continue;
                        LuisYPuntoImportante destino = puntoPorPoi.get(clavePoi(demanda.pois().get(p)));
                        lista.add(new Candidato(zx[a], zy[a], px[p], py[p], puntoZona(a), destino,
                                recta, ganancia(zx[a], zy[a], px[p], py[p])));
                    }
                }
            }
            lista.sort(Comparator.comparingDouble(Candidato::ganancia).reversed());
            return lista;
        }

        private LuisYPuntoImportante puntoZona(int z) {
            return puntoDeZona(demanda.zonas().get(z));
        }

        /**
         * Paradas de la candidata, en el orden en que las recorre: extremos, POIs populares del
         * corredor (repartidos a lo largo) y zonas con demanda hasta llegar a {@code paradas}.
         * {@code null} si el corredor no ofrece los POIs mínimos.
         */
        List<LuisYPuntoImportante> paraderos(Candidato c, TipoRuta tipo, int minPois, int paradas) {
            // El destino de una alimentadora ya es un POI popular y cuenta como uno de los mínimos.
            int minIntermedios = tipo == TipoRuta.TRONCAL ? minPois : Math.max(0, minPois - 1);
            int objetivo = Math.max(minIntermedios,
                    tipo == TipoRuta.TRONCAL ? POIS_OBJETIVO_TRONCAL : POIS_OBJETIVO_ALIMENTADORA - 1);
            double dx = c.bx() - c.ax(), dy = c.by() - c.ay();
            double largo = Math.max(1, Math.hypot(dx, dy));
            double ux = dx / largo, uy = dy / largo;
            List<double[]> elegidos = null; // {poi, t}
            for (double ancho : new double[]{ANCHO_CORREDOR_METROS, ANCHO_CORREDOR_METROS * 1.7}) {
                List<double[]> enCorredor = new ArrayList<>();
                for (int p = 0; p < np; p++) {
                    if (!popular[p]) continue;
                    double qx = px[p] - c.ax(), qy = py[p] - c.ay();
                    double t = (qx * ux + qy * uy) / largo;
                    double lateral = Math.abs(qx * uy - qy * ux);
                    if (t < 0.06 || t > 0.94 || lateral > ancho) continue;
                    if (Math.hypot(px[p] - c.bx(), py[p] - c.by()) < SEPARACION_POIS_METROS) continue;
                    enCorredor.add(new double[]{p, t});
                }
                enCorredor.sort(Comparator.comparingDouble((double[] e) -> puntaje[(int) e[0]]).reversed());
                List<double[]> tomados = new ArrayList<>();
                for (double[] e : enCorredor) {
                    if (tomados.size() >= objetivo) break;
                    boolean cerca = false;
                    for (double[] o : tomados) {
                        double metros = Math.hypot(px[(int) e[0]] - px[(int) o[0]], py[(int) e[0]] - py[(int) o[0]]);
                        if (Math.abs(e[1] - o[1]) < 0.1 || metros < SEPARACION_POIS_METROS) cerca = true;
                    }
                    if (!cerca) tomados.add(e);
                }
                if (tomados.size() >= minIntermedios) {
                    elegidos = tomados;
                    break;
                }
            }
            if (elegidos == null) return null;

            List<double[]> paradasT = new ArrayList<>(); // {t, tipo 0 = POI / 1 = zona, índice}
            List<double[]> ocupadas = new ArrayList<>(); // {x, y}
            ocupadas.add(new double[]{c.ax(), c.ay()});
            ocupadas.add(new double[]{c.bx(), c.by()});
            for (double[] e : elegidos) {
                paradasT.add(new double[]{e[1], 0, e[0]});
                ocupadas.add(new double[]{px[(int) e[0]], py[(int) e[0]]});
            }
            int faltan = paradas - 2 - elegidos.size();
            if (faltan > 0) {
                List<double[]> zonas = new ArrayList<>(); // {necesidad, z, t}
                for (int z = 0; z < nz; z++) {
                    if (distSegmento(zx[z], zy[z], c.ax(), c.ay(), c.bx(), c.by())
                            > EberthMCoberturaDemanda.RADIO_ZONA_METROS) continue;
                    double t = ((zx[z] - c.ax()) * ux + (zy[z] - c.ay()) * uy) / largo;
                    if (t < 0.05 || t > 0.95) continue;
                    zonas.add(new double[]{necesidad(z), z, t});
                }
                zonas.sort(Comparator.comparingDouble((double[] z) -> z[0]).reversed());
                for (double[] z : zonas) {
                    if (faltan <= 0) break;
                    boolean cerca = false;
                    for (double[] o : ocupadas) {
                        if (Math.hypot(zx[(int) z[1]] - o[0], zy[(int) z[1]] - o[1]) < SEPARACION_PARADAS_METROS) cerca = true;
                    }
                    if (cerca) continue;
                    paradasT.add(new double[]{z[2], 1, z[1]});
                    ocupadas.add(new double[]{zx[(int) z[1]], zy[(int) z[1]]});
                    faltan--;
                }
            }
            paradasT.sort(Comparator.comparingDouble(e -> e[0]));
            List<LuisYPuntoImportante> resultado = new ArrayList<>();
            resultado.add(c.origen());
            for (double[] e : paradasT) {
                resultado.add(e[1] == 0 ? puntoPorPoi.get(clavePoi(demanda.pois().get((int) e[2])))
                        : puntoZona((int) e[2]));
            }
            resultado.add(c.destino());
            return resultado;
        }

        /** POIs populares junto al trazado ya construido (a ≤ 400 m de sus tramos). */
        int popularesCerca(boolean[][] alcance) {
            int n = 0;
            for (int p = 0; p < np; p++) if (popular[p] && alcance[1][p]) n++;
            return n;
        }

        /**
         * Una ruta del tipo pedido o {@code null}. Se exige llegar a la longitud mínima y tocar
         * POIs populares; si el mapa no lo permite se relaja poco a poco (nunca bajo 4 km ni sin
         * ningún POI). Una ruta demasiado corta se descarta y las siguientes se buscan más largas:
         * antes de trazar se estima la longitud con el rodeo que midieron los trazados anteriores.
         */
        EberthMServicioMicro crearRuta(TipoRuta tipo, int numero, List<EberthMServicioMicro> todas,
                                       Opciones op) {
            boolean troncal = tipo == TipoRuta.TRONCAL;
            LuisYCalculadorTrayecto calculador = new LuisYCalculadorTrayecto(red);
            for (int[] nivel : NIVELES) {
                int minPois = nivel[0];
                double min = Math.max(longitudes.absolutaMin(),
                        (troncal ? longitudes.troncalMin() : longitudes.alimentadoraMin()) * nivel[1] / 100.0);
                double max = troncal ? longitudes.troncalMax() : longitudes.alimentadoraMax();
                double rectaMin = 0.9 * min / rodeo;
                List<Candidato> candidatos = candidatos(tipo, rectaMin, Math.max(rectaMin, max));
                int trazadas = 0;
                for (Candidato c : candidatos) {
                    if (trazadas >= TRAZADOS_POR_NIVEL) break;
                    List<LuisYPuntoImportante> paraderos = paraderos(c, tipo, minPois, op.paradas());
                    if (paraderos == null) continue;
                    double polilinea = longitudPolilinea(paraderos);
                    double estimada = polilinea * rodeo;
                    if (estimada < min * 0.92 || estimada > max * 1.08) continue;
                    trazadas++;
                    LuisYTrayecto ida;
                    try {
                        ida = calculador.calcular("micro-auto-" + java.util.UUID.randomUUID(), PREFIJO_NOMBRE + numero,
                                "Trafiqueishon", "SERVICIO", paraderos);
                    } catch (RuntimeException ex) {
                        LOG.log(Level.FINE, "Ruta autogenerada descartada: " + c.origen().nombre()
                                + " → " + c.destino().nombre(), ex);
                        continue;
                    }
                    double metros = ida.distanciaMetros();
                    if (metros <= 0) continue;
                    rodeo = Math.clamp(0.6 * rodeo + 0.4 * metros / polilinea, 1.05, 2.0);
                    if (metros < min || metros > max * 1.05) continue;
                    boolean[][] alcance = EberthMCoberturaDemanda.alcance(demanda, ida.edges());
                    if (popularesCerca(alcance) < minPois) continue;
                    double[] impacto = impacto(alcance);
                    if (impacto[0] <= 0) continue;
                    // Las rutas pasan por las zonas de origen de la demanda: paradas a lo largo del trazado.
                    List<LuisYPuntoImportante> conZonas = paradasEnZonas(ida, paraderos);
                    if (conZonas.size() > paraderos.size()) {
                        try {
                            ida = calculador.calcular("micro-auto-" + java.util.UUID.randomUUID(),
                                    PREFIJO_NOMBRE + numero, "Trafiqueishon", "SERVICIO", conZonas);
                            paraderos = conZonas;
                        } catch (RuntimeException ex) {
                            LOG.log(Level.FINE, "Sin paradas extra en las zonas de la ruta", ex);
                        }
                    }
                    EberthMServicioMicro servicio = construir(numero, tipo, todas, paraderos, ida, impacto, op);
                    if (servicio == null) continue;
                    aplicar(alcance);
                    return servicio;
                }
            }
            return null;
        }

        /**
         * Paradas de la ruta más una en cada zona de origen de la demanda que queda junto al trazado y
         * aún sin parada a ≤ {@value #RADIO_PARADA_ZONA_METROS} m (allí la gente sale a esperar el micro).
         * Se colocan sobre nodos del propio trazado, así que no lo desvían; las zonas más demandantes
         * van primero y el total no pasa de {@value #MAX_PARADAS_RUTA}. En el orden en que se recorren.
         */
        private List<LuisYPuntoImportante> paradasEnZonas(LuisYTrayecto ida, List<LuisYPuntoImportante> paraderos) {
            List<PieroGEdge> edges = ida.edges();
            List<PieroGNodo> nodos = new ArrayList<>(edges.size() + 1);
            nodos.add(edges.getFirst().origen);
            for (PieroGEdge e : edges) nodos.add(e.destino);
            double[] nx = new double[nodos.size()], ny = new double[nodos.size()];
            for (int i = 0; i < nx.length; i++) {
                nx[i] = (nodos.get(i).lon - lon0) * kx;
                ny[i] = (nodos.get(i).lat - lat0) * METROS_POR_GRADO;
            }
            // Posición en el trazado de cada parada ya elegida (para conservar el orden de recorrido).
            List<double[]> paradas = new ArrayList<>(); // {indice de nodo, x, y}
            List<LuisYPuntoImportante> puntos = new ArrayList<>();
            int desde = 0;
            for (LuisYPuntoImportante p : paraderos) {
                double x = (p.lon() - lon0) * kx, y = (p.lat() - lat0) * METROS_POR_GRADO;
                int mejor = desde;
                double mejorD = Double.MAX_VALUE;
                for (int i = desde; i < nx.length; i++) {
                    double d = Math.hypot(nx[i] - x, ny[i] - y);
                    if (d < mejorD) {
                        mejorD = d;
                        mejor = i;
                    }
                }
                desde = mejor;
                paradas.add(new double[]{mejor, nx[mejor], ny[mejor]});
                puntos.add(p);
            }
            Integer[] porDemanda = new Integer[nz];
            for (int z = 0; z < nz; z++) porDemanda[z] = z;
            java.util.Arrays.sort(porDemanda, Comparator.comparingDouble((Integer z) -> demanda.viajesDesdeZona(z)).reversed());
            int agregadas = 0;
            for (int z : porDemanda) {
                if (paradas.size() >= MAX_PARADAS_RUTA) break;
                int mejor = -1;
                double mejorD = Double.MAX_VALUE;
                for (int i = 0; i < nx.length; i++) {
                    double d = Math.hypot(nx[i] - zx[z], ny[i] - zy[z]);
                    if (d < mejorD) {
                        mejorD = d;
                        mejor = i;
                    }
                }
                if (mejor < 0 || mejorD > RADIO_PARADA_ZONA_METROS) continue;
                boolean cubierta = false;
                for (double[] p : paradas) {
                    if (Math.hypot(p[1] - zx[z], p[2] - zy[z]) <= RADIO_PARADA_ZONA_METROS) cubierta = true;
                    if (p[0] == mejor) cubierta = true; // ya hay parada en ese nodo
                }
                if (cubierta) continue;
                EberthMDemandaCiudad.Zona zona = demanda.zonas().get(z);
                PieroGNodo nodo = nodos.get(mejor);
                paradas.add(new double[]{mejor, nx[mejor], ny[mejor]});
                puntos.add(new LuisYPuntoImportante("parada-zona-" + zona.id(), "Parada zona " + (zona.id() + 1),
                        nodo.lon, nodo.lat, TipoPunto.PARADA));
                agregadas++;
            }
            if (agregadas == 0) return paraderos;
            // Orden de recorrido; origen y destino siguen siendo los extremos.
            Integer[] orden = new Integer[puntos.size()];
            for (int i = 0; i < orden.length; i++) orden[i] = i;
            java.util.Arrays.sort(orden, Comparator.comparingDouble(i -> paradas.get(i)[0]));
            List<LuisYPuntoImportante> resultado = new ArrayList<>(puntos.size());
            resultado.add(paraderos.getFirst());
            for (int i : orden) {
                LuisYPuntoImportante p = puntos.get(i);
                if (p != paraderos.getFirst() && p != paraderos.getLast()) resultado.add(p);
            }
            resultado.add(paraderos.getLast());
            return resultado;
        }

        private double longitudPolilinea(List<LuisYPuntoImportante> paraderos) {
            double suma = 0;
            for (int i = 1; i < paraderos.size(); i++) suma += metros(paraderos.get(i - 1), paraderos.get(i));
            return suma;
        }

        /**
         * Servicio completo (ida y vuelta) con pasajeros y flota según la demanda que sirve (los
         * viajes que otra ruta ya cubría cuentan a medias, se reparten) y la duración de su vuelta:
         * una ruta larga necesita más unidades para mantener la frecuencia.
         */
        private EberthMServicioMicro construir(int numero, TipoRuta tipo, List<EberthMServicioMicro> todas,
                List<LuisYPuntoImportante> paraderos, LuisYTrayecto ida, double[] impacto, Opciones op) {
            double fraccionPico = demanda.totalViajes() > 0
                    ? (double) demanda.viajesPico() / demanda.totalViajes()
                    : EberthMParametrosCiudad.FRACCION_HORA_PICO;
            // Pasajeros reales por hora en punta: la simulación los inyecta con la escala ×10 por defecto.
            double demandaHora = (impacto[0] + 0.5 * impacto[1]) * fraccionPico;
            int pasajeros = (int) Math.clamp(Math.round(demandaHora), 10, EberthMServicioMicro.MAX_PASAJEROS);
            int unidades;
            if (op.unidades() > 0) {
                unidades = op.unidades();
            } else {
                double vuelta = vueltaCompletaMinutos(ida.tiempoSegundos(), op.frecuenciaMin(), paraderos.size());
                unidades = unidadesParaRuta(demandaHora, vuelta, op.frecuenciaMin(), op.frecuenciaPuntaMin());
            }
            // El tope de pantalla se reparte: las primeras rutas no se quedan con toda la flota.
            int cuota = Math.max(MIN_UNIDADES, flotaRestante / Math.max(1, rutasPendientes));
            unidades = Math.max(1, Math.min(Math.min(unidades, cuota), flotaRestante));
            try {
                EberthMServicioMicro servicio = EberthMGestorServicios.construirServicio(red,
                        "micro-auto-" + java.util.UUID.randomUUID(),
                        PREFIJO_NOMBRE + numero + (tipo == TipoRuta.TRONCAL ? " (Troncal)" : " (Alimentadora)"),
                        paraderos, false, EberthMTipoRetorno.INVERSO, List.of(), op.frecuenciaMin(),
                        op.frecuenciaPuntaMin(), unidades, pasajeros, colorAleatorio(todas));
                flotaRestante -= unidades;
                return servicio;
            } catch (RuntimeException ex) {
                LOG.log(Level.FINE, "Ruta autogenerada descartada: " + paraderos.getFirst().nombre()
                        + " → " + paraderos.getLast().nombre(), ex);
                return null;
            }
        }
    }

    /** Distancia de un punto a un segmento, en metros locales. */
    private static double distSegmento(double x, double y, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double largo2 = dx * dx + dy * dy;
        double t = largo2 <= 0 ? 0 : Math.clamp(((x - ax) * dx + (y - ay) * dy) / largo2, 0, 1);
        return Math.hypot(x - (ax + t * dx), y - (ay + t * dy));
    }

    /**
     * Unidades de una ruta: las que exige la demanda pico de la ruta ({@code demanda ÷ 18 pasajeros ×
     * vuelta en horas}) y, como mínimo, las que mantienen la frecuencia normal ({@code vuelta ÷
     * frecuencia}). Sin pasar de las que caben a la frecuencia de punta ({@code vuelta ÷ frecuencia
     * de punta}), pues más unidades irían en pelotón. Mínimo {@value #MIN_UNIDADES}.
     *
     * @param demandaHora pasajeros por hora (simulados) que sirve la ruta en hora punta
     * @param vueltaMin duración de una vuelta completa en minutos
     */
    public static int unidadesParaRuta(double demandaHora, double vueltaMin, int frecuenciaMin,
                                       int frecuenciaPuntaMin) {
        double porDemanda = Math.ceil(demandaHora / PASAJEROS_POR_MICRO * (vueltaMin / 60.0));
        double paraFrecuencia = Math.ceil(vueltaMin / Math.max(1, frecuenciaMin));
        double tope = Math.ceil(vueltaMin / Math.max(1, frecuenciaPuntaMin));
        double unidades = Math.min(Math.max(porDemanda, paraFrecuencia), Math.max(MIN_UNIDADES, tope));
        return (int) Math.clamp(unidades, MIN_UNIDADES, FLOTA_MAXIMA_PANTALLA);
    }

    private static String clavePoi(EberthMPoi p) {
        return p.nombre() + "|" + p.lon() + "|" + p.lat();
    }

    private static LuisYPuntoImportante puntoDeZona(EberthMDemandaCiudad.Zona z) {
        return new LuisYPuntoImportante("zona-" + z.id(), z.nombre(), z.lon(), z.lat(),
                TipoPunto.PARADA);
    }

    private static double metros(double lon1, double lat1, double lon2, double lat2) {
        double dx = (lon1 - lon2) * METROS_POR_GRADO * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        return Math.hypot(dx, (lat1 - lat2) * METROS_POR_GRADO);
    }

    /** Hasta {@code maxIntermedias} POIs de mayor prioridad a ≤ 300 m del trazado, separados entre sí y de los extremos. */
    private List<LuisYPuntoImportante> paradasIntermedias(List<PieroGEdge> trazado,
            LuisYPuntoImportante origen, LuisYPuntoImportante destino, int maxIntermedias) {
        List<EberthMIndicePoi.Candidato> cerca = new ArrayList<>(indicePoi.candidatosEnRuta(trazado));
        Collections.shuffle(cerca, azar);
        cerca.sort(Comparator.comparingInt(
                (EberthMIndicePoi.Candidato c) -> peso(puntoDePoi.get(c.poi()))).reversed());

        List<EberthMIndicePoi.Candidato> elegidos = new ArrayList<>();
        List<LuisYPuntoImportante> ocupados = new ArrayList<>(List.of(origen, destino));
        for (EberthMIndicePoi.Candidato c : cerca) {
            if (elegidos.size() >= maxIntermedias) break;
            LuisYPuntoImportante punto = puntoDePoi.get(c.poi());
            if (ocupados.stream().anyMatch(o -> metros(o, punto) < SEPARACION_PARADAS_METROS)) continue;
            elegidos.add(c);
            ocupados.add(punto);
        }
        elegidos.sort(Comparator.comparingInt(EberthMIndicePoi.Candidato::indiceEdge));
        return elegidos.stream().map(c -> puntoDePoi.get(c.poi())).toList();
    }

    /**
     * Pasajeros por hora de la ruta: suma de prioridades de los POIs que conecta × 10 × el
     * multiplicador de población. Un hospital (5) y una universidad (4) ya aportan 90.
     */
    static int demandaBase(int sumaPrioridades, double multiplicadorPoblacion) {
        return (int) Math.clamp(Math.round(
                (double) sumaPrioridades * DEMANDA_POR_PRIORIDAD * multiplicadorPoblacion),
                0, EberthMServicioMicro.MAX_PASAJEROS);
    }

    /**
     * Escala logarítmica respecto de 100 000 habitantes (Juliaca ≈ 1,09; Lima ≈ 1,4), acotada
     * a [0,6; 1,6] para que ni un pueblo ni una megalópolis desborden la flota.
     */
    static double multiplicadorPoblacion(long poblacion) {
        double razon = Math.max(1, poblacion) / (double) POBLACION_REFERENCIA;
        return Math.clamp(1 + Math.log10(razon) * 0.4, 0.6, 1.6);
    }

    /**
     * Frecuencia de salida de la ciudad: 5 min con más de 1 M de habitantes, 8 min con más de
     * 500 mil y 10 min en el resto. Sin parámetros de ciudad, 10 min.
     */
    public static int frecuenciaPorCiudad(EberthMParametrosCiudad ciudad) {
        return ciudad == null ? FRECUENCIA_NORMAL_MIN : ciudad.frecuenciaBaseMinutos();
    }

    /**
     * Duración de una vuelta completa de una unidad: ida y vuelta por el trazado, las paradas
     * intermedias y la espera en cada terminal (el simulador espera una frecuencia en cada extremo).
     */
    static double vueltaCompletaMinutos(double tiempoIdaSegundos, int frecuenciaMin, int paradas) {
        double minutosViaje = 2 * tiempoIdaSegundos / 60.0;
        double minutosParadas = 2 * paradas * SEGUNDOS_POR_PARADA / 60.0;
        return minutosViaje + minutosParadas + 2.0 * frecuenciaMin;
    }

    /**
     * Unidades de una ruta: {@code (demanda pico de la ruta ÷ 18) × (vuelta completa ÷ 60 min)},
     * es decir, cuántas micros hacen falta para llevar esa demanda en la hora pico. La demanda
     * pico es {@code población × % transporte × 0,15 ÷ 10} (ver
     * {@link EberthMParametrosCiudad#demandaPicoSimuladaHora}) repartida entre las rutas de la
     * ciudad. Mínimo 2 unidades; y nunca más de las que caben a la frecuencia de punta
     * ({@code vuelta ÷ frecuencia de punta}): más unidades que eso solo irían en pelotón.
     *
     * @param vueltaMin duración de una vuelta completa en minutos
     */
    public static int unidadesPorRuta(EberthMParametrosCiudad ciudad, int rutas, double vueltaMin,
                                      int frecuenciaPuntaMin) {
        if (ciudad == null) return MIN_UNIDADES;
        double demandaRuta = ciudad.demandaPicoSimuladaHora() / Math.max(1, rutas);
        double porDemanda = demandaRuta / PASAJEROS_POR_MICRO * (vueltaMin / 60.0);
        double tope = Math.ceil(vueltaMin / Math.max(1, frecuenciaPuntaMin));
        double unidades = Math.min(Math.ceil(porDemanda), Math.max(MIN_UNIDADES, tope));
        return (int) Math.clamp(unidades, MIN_UNIDADES, FLOTA_MAXIMA_PANTALLA);
    }

    /** Pasajeros por hora que mueve una flota de {@code rutas} rutas con {@code unidades} micros cada una (vuelta típica). */
    public static double capacidadHoraPunta(int rutas, int unidades) {
        return (double) rutas * unidades * (60.0 / VUELTA_TIPICA_MIN) * PASAJEROS_POR_MICRO;
    }

    /** Unidades sugeridas por ruta para una vuelta típica de {@value #VUELTA_TIPICA_MIN} min. */
    public static int unidadesSugeridas(EberthMParametrosCiudad ciudad, int rutas) {
        int frecuencia = frecuenciaPorCiudad(ciudad);
        return unidadesPorRuta(ciudad, rutas, VUELTA_TIPICA_MIN,
                EberthMServicioMicro.frecuenciaPuntaPorDefecto(frecuencia));
    }

    /** Flota por ruta para las 5 rutas habituales ({@value #MAX_RUTAS}). */
    public static int unidadesSugeridas(EberthMParametrosCiudad ciudad) {
        return unidadesSugeridas(ciudad, MAX_RUTAS);
    }

    /** Prioridad del tipo de punto: 0 = no se considera POI importante. */
    static int prioridad(TipoPunto tipo) {
        if (tipo == null) return 0;
        return switch (tipo) {
            case SALUD, MERCADO -> 5;
            case EDUCACION, TERMINAL -> 4;
            case TRANSPORTE, COMERCIO, INSTITUCION -> 3;
            case TURISMO, PLAZA -> 2;
            case PARQUE, DEPORTE, POI -> 1;
            default -> 0;
        };
    }

    private static int peso(LuisYPuntoImportante p) {
        return Math.max(1, prioridad(p.tipo()));
    }

    /** Elige al azar con probabilidad proporcional a la prioridad. */
    private LuisYPuntoImportante elegirPonderado(List<LuisYPuntoImportante> lista,
                                                 LuisYPuntoImportante excluido) {
        int total = lista.stream().filter(p -> p != excluido).mapToInt(this::pesoConZonas).sum();
        int tirada = azar.nextInt(Math.max(1, total));
        for (LuisYPuntoImportante p : lista) {
            if (p == excluido) continue;
            tirada -= pesoConZonas(p);
            if (tirada < 0) return p;
        }
        return lista.getLast();
    }

    /** 3 km, o menos en ciudades pequeñas: nunca más del 40 % de la mayor distancia entre POIs. */
    private static double distanciaMinimaEfectiva(List<LuisYPuntoImportante> puntos) {
        double mayor = 0;
        for (int i = 0; i < puntos.size(); i++) {
            for (int j = i + 1; j < puntos.size(); j++) {
                mayor = Math.max(mayor, metros(puntos.get(i), puntos.get(j)));
            }
        }
        return Math.min(DISTANCIA_MINIMA_METROS, Math.max(300, mayor * 0.4));
    }

    private static double solape(List<PieroGEdge> nuevo, List<Set<PieroGEdge>> trazados) {
        if (nuevo.isEmpty()) return 0;
        double peor = 0;
        for (Set<PieroGEdge> otro : trazados) {
            long comunes = nuevo.stream().filter(otro::contains).count();
            peor = Math.max(peor, (double) comunes / nuevo.size());
        }
        return peor;
    }

    private static Set<PieroGEdge> identidad(List<PieroGEdge> edges) {
        Set<PieroGEdge> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(edges);
        return set;
    }

    private static int siguienteNumero(List<EberthMServicioMicro> existentes) {
        int mayor = 0;
        for (EberthMServicioMicro s : existentes) {
            if (!s.nombre().startsWith(PREFIJO_NOMBRE)) continue;
            try {
                mayor = Math.max(mayor, Integer.parseInt(s.nombre().substring(PREFIJO_NOMBRE.length()).split(" ")[0]));
            } catch (NumberFormatException ignorado) {
                // Nombre editado por el usuario: no cuenta para la numeración.
            }
        }
        return mayor + 1;
    }

    /** Color de tono aleatorio, saturado y distinto (≥ 25° de tono) de los ya usados. */
    private String colorAleatorio(List<EberthMServicioMicro> usadas) {
        Set<String> usados = new HashSet<>();
        for (EberthMServicioMicro s : usadas) usados.add(s.color().toUpperCase(Locale.ROOT));
        String color = null;
        for (int i = 0; i < 30; i++) {
            float tono = azar.nextFloat();
            int rgb = java.awt.Color.HSBtoRGB(tono, 0.9f, 1f) & 0xFFFFFF;
            color = String.format("#%06X", rgb);
            if (usados.stream().noneMatch(u -> tonosCercanos(u, tono))) return color;
        }
        return usados.contains(color) ? EberthMGestorServicios.siguienteColor(usadas) : color;
    }

    private static boolean tonosCercanos(String hex, float tono) {
        try {
            int rgb = Integer.parseInt(hex.substring(1), 16);
            float[] hsb = java.awt.Color.RGBtoHSB((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, null);
            float d = Math.abs(hsb[0] - tono);
            return Math.min(d, 1 - d) < 25f / 360f;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static double metros(LuisYPuntoImportante a, LuisYPuntoImportante b) {
        double dx = (a.lon() - b.lon()) * METROS_POR_GRADO * Math.cos(Math.toRadians((a.lat() + b.lat()) / 2));
        double dy = (a.lat() - b.lat()) * METROS_POR_GRADO;
        return Math.hypot(dx, dy);
    }
}
