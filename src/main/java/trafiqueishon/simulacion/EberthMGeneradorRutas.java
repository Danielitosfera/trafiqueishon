package trafiqueishon.simulacion;

import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.geo.PieroGNodo;
import trafiqueishon.geo.PieroGPathfinder;
import trafiqueishon.geo.PieroGRutaResult;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.ruta.LuisYPuntoImportante.TipoPunto;

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
    /** Por encima de esta demanda (pasajeros/hora) la ruta sale cada {@link #FRECUENCIA_ALTA_MIN} min. */
    static final int UMBRAL_DEMANDA_ALTA = 150;
    static final int FRECUENCIA_ALTA_MIN = 5;
    static final int FRECUENCIA_NORMAL_MIN = 10;
    /** Pasajeros que carga una micro por viaje (ver {@link EberthMDemandaPasajeros#ABORDAJE_MAXIMO}). */
    static final int PASAJEROS_POR_MICRO = EberthMDemandaPasajeros.ABORDAJE_MAXIMO;
    static final int MIN_UNIDADES = 2;
    static final int MAX_UNIDADES = 20;
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

    /** Genera entre {@value #MIN_RUTAS} y {@value #MAX_RUTAS} rutas (menos si el mapa no da más). */
    public List<EberthMServicioMicro> generar(List<EberthMServicioMicro> existentes) {
        return generar(existentes, MIN_RUTAS + azar.nextInt(MAX_RUTAS - MIN_RUTAS + 1));
    }

    /** Valores elegidos por el usuario: cantidad de rutas, paradas y unidades por ruta, frecuencias normal y de hora punta. */
    /** @param unidades micros por ruta; {@code 0} = calcularlas con {@link #unidadesSugeridas} */
    public record Opciones(int rutas, int paradas, int unidades, int frecuenciaMin,
                           int frecuenciaPuntaMin) {
        public Opciones {
            if (rutas < 1 || paradas < 2 || unidades < 0 || frecuenciaMin < 1
                    || frecuenciaPuntaMin < 1 || frecuenciaPuntaMin > frecuenciaMin) {
                throw new IllegalArgumentException("Opciones de generación no válidas");
            }
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

        for (int n = 0; n < cantidad; n++) {
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
        int frecuencia = opciones == null ? frecuenciaPorDemanda(demanda) : opciones.frecuenciaMin();
        int frecuenciaPunta = opciones == null ? 0 : opciones.frecuenciaPuntaMin();
        int unidades = opciones == null ? unidadesNecesarias(demanda, frecuencia)
                : opciones.unidades() > 0 ? opciones.unidades() : unidadesSugeridas(ciudad);
        try {
            return EberthMGestorServicios.construirServicio(red, "micro-auto-" + java.util.UUID.randomUUID(),
                    PREFIJO_NOMBRE + numero, paraderos, false, EberthMTipoRetorno.INVERSO, List.of(),
                    frecuencia, frecuenciaPunta, unidades, demanda, colorAleatorio(todas));
        } catch (RuntimeException ex) {
            LOG.log(Level.FINE, "Ruta autogenerada descartada: " + origen.nombre()
                    + " → " + destino.nombre(), ex);
            return null;
        }
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

    /** Habitantes que atiende una micro de la flota de una ruta. */
    static final int HABITANTES_POR_UNIDAD = 5_000;

    /**
     * Flota por ruta según la ciudad: {@code población × %transporte público ÷ 5000} (Juliaca:
     * 280 000 × 65 % ÷ 5000 = 36). Sin parámetros de ciudad devuelve el mínimo.
     */
    public static int unidadesSugeridas(EberthMParametrosCiudad ciudad) {
        if (ciudad == null) return MIN_UNIDADES;
        double usuarios = ciudad.getPoblacionTotal() * ciudad.getPorcentajeTransportePublico() / 100.0;
        return (int) Math.clamp(Math.round(usuarios / HABITANTES_POR_UNIDAD), 1, EberthMServicioMicro.MAX_UNIDADES);
    }

    /** Más de {@value #UMBRAL_DEMANDA_ALTA} pasajeros/hora → cada 5 min; si no, cada 10. */
    static int frecuenciaPorDemanda(int demanda) {
        return demanda > UMBRAL_DEMANDA_ALTA ? FRECUENCIA_ALTA_MIN : FRECUENCIA_NORMAL_MIN;
    }

    /** {@code ceil((demanda / 18) / (60 / frecuencia))}, entre 2 y 20 unidades. */
    static int unidadesNecesarias(int demanda, int frecuenciaMin) {
        double microsPorHora = (double) demanda / PASAJEROS_POR_MICRO;
        double viajesPorHora = 60.0 / frecuenciaMin;
        return (int) Math.clamp((long) Math.ceil(microsPorHora / viajesPorHora), MIN_UNIDADES, MAX_UNIDADES);
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
        int total = lista.stream().filter(p -> p != excluido).mapToInt(EberthMGeneradorRutas::peso).sum();
        int tirada = azar.nextInt(Math.max(1, total));
        for (LuisYPuntoImportante p : lista) {
            if (p == excluido) continue;
            tirada -= peso(p);
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
                mayor = Math.max(mayor, Integer.parseInt(s.nombre().substring(PREFIJO_NOMBRE.length())));
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
