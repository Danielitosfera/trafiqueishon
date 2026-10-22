package trafiqueishon.simulacion;

import trafiqueishon.geo.PieroGEdge;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Genera eventos de tráfico de forma orgánica, una vez por minuto simulado, según los
 * {@link EberthMParametrosCiudad}. Los eventos caen en el punto medio de una vía
 * {@code primary} o {@code secondary} elegida al azar y caducan solos.
 * No es seguro entre hilos: lo usa únicamente el hilo del motor.
 */
public final class EberthMGeneradorEventos {

    /** Probabilidad por minuto simulado de que aparezca una trancadera fuera de hora punta. */
    public static final double PROB_TRANCADERA_BASE = 0.02;
    /** En hora punta la probabilidad sube un 200 % (se triplica). */
    public static final double AUMENTO_HORA_PUNTA = 2.0;
    /** Probabilidad de accidente por minuto según la tasa de accidentes de la ciudad. */
    public static final double PROB_ACCIDENTE_BAJA = 0.003;
    public static final double PROB_ACCIDENTE_MEDIA = 0.008;
    public static final double PROB_ACCIDENTE_ALTA = 0.02;
    /** Duración media de un aguacero (15-40 min simulados), para convertir % de lluvia en llegadas/min. */
    public static final double DURACION_MEDIA_LLUVIA_MIN = 27.5;
    /** Tope de la fracción de tiempo lloviendo: con 100 % la tasa de llegadas sería infinita. */
    static final double LLUVIA_MAXIMA = 0.95;

    public static final int MAX_TRANCADERAS = 6;
    public static final int MAX_ACCIDENTES = 3;
    public static final int MAX_LLUVIAS = 2;
    /** Duración de un policía de tránsito, en minutos simulados. */
    public static final int DURACION_POLICIA_MINUTOS = 10;

    private static final double RADIO_LLUVIA_MIN = 500;
    private static final double RADIO_LLUVIA_MAX = 2_000;

    private record Activo(EberthMEventoTrafico evento, int expiraMinuto) {}

    private final RandomGenerator azar;
    private final List<PieroGEdge> vias = new ArrayList<>();
    private final List<Activo> activos = new ArrayList<>();
    private int contador;

    public EberthMGeneradorEventos(RandomGenerator azar) {
        this.azar = azar;
    }

    /** Vías candidatas: solo {@code primary} y {@code secondary}; sin ellas, cualquier vía de la red. */
    public void setVias(List<PieroGEdge> edges) {
        vias.clear();
        for (PieroGEdge e : edges) {
            if (esPrincipal(e.highway)) vias.add(e);
        }
        if (vias.isEmpty()) vias.addAll(edges);
    }

    private static boolean esPrincipal(String highway) {
        return highway != null && (highway.startsWith("primary") || highway.startsWith("secondary"));
    }

    public void reiniciar() {
        activos.clear();
        contador = 0;
    }

    public static double probabilidadTrancadera(boolean horaPunta) {
        return PROB_TRANCADERA_BASE * (horaPunta ? 1 + AUMENTO_HORA_PUNTA : 1);
    }

    public static double probabilidadAccidente(EberthMParametrosCiudad.TasaAccidentes tasa,
                                               EberthMTipoDia dia) {
        return Math.min(1, probabilidadAccidente(tasa) * dia.factorAccidentes());
    }

    public static double probabilidadAccidente(EberthMParametrosCiudad.TasaAccidentes tasa) {
        return switch (tasa) {
            case ALTA -> PROB_ACCIDENTE_ALTA;
            case MEDIA -> PROB_ACCIDENTE_MEDIA;
            case BAJA -> PROB_ACCIDENTE_BAJA;
        };
    }

    /**
     * Probabilidad por minuto de que empiece un aguacero, tal que la ciudad esté lloviendo
     * aproximadamente {@code porcentajeCiudad} % del tiempo (al menos una lluvia activa). Jugar
     * el porcentaje directamente cada minuto la haría llover casi siempre (35 % por minuto ≈ un
     * aguacero cada 3 min).
     *
     * <p>Con hasta {@link #MAX_LLUVIAS} = 2 lluvias a la vez es un sistema de pérdida de Erlang:
     * con carga {@code a = λ·D} (D = duración media), la ciudad está seca con probabilidad
     * {@code 1 / (1 + a + a²/2)}. Despejando para una fracción lluviosa {@code p}:
     * {@code a = √((1 + p) / (1 − p)) − 1}.</p>
     */
    public static double probabilidadLluvia(double porcentajeCiudad) {
        double p = Math.clamp(porcentajeCiudad / 100.0, 0, LLUVIA_MAXIMA);
        double carga = Math.sqrt((1 + p) / (1 - p)) - 1;
        return carga / DURACION_MEDIA_LLUVIA_MIN;
    }

    /** Eventos generados cuya duración terminó en {@code minuto}; dejan de estar activos. */
    public List<EberthMEventoTrafico> expirados(int minuto) {
        List<EberthMEventoTrafico> fuera = new ArrayList<>();
        activos.removeIf(a -> {
            if (a.expiraMinuto() > minuto) return false;
            fuera.add(a.evento());
            return true;
        });
        return fuera;
    }

    /** Sortea los eventos de un minuto simulado. */
    public List<EberthMEventoTrafico> generar(int minuto, boolean horaPunta,
                                              EberthMParametrosCiudad ciudad) {
        return generar(minuto, horaPunta, ciudad, EberthMTipoDia.LABORAL);
    }

    public List<EberthMEventoTrafico> generar(int minuto, boolean horaPunta,
                                              EberthMParametrosCiudad ciudad, EberthMTipoDia dia) {
        List<EberthMEventoTrafico> nuevos = new ArrayList<>();
        if (vias.isEmpty()) return nuevos;
        if (cuantos(EberthMTipoEvento.TRANCADERA) < MAX_TRANCADERAS
                && azar.nextDouble() < probabilidadTrancadera(horaPunta)) {
            nuevos.add(crear(EberthMTipoEvento.TRANCADERA, minuto, 10 + azar.nextInt(16), 0));
        }
        if (cuantos(EberthMTipoEvento.ACCIDENTE) < MAX_ACCIDENTES
                && azar.nextDouble() < probabilidadAccidente(ciudad.getTasaAccidentes(), dia)) {
            nuevos.add(crear(EberthMTipoEvento.ACCIDENTE, minuto, 8 + azar.nextInt(13), 0));
        }
        if (cuantos(EberthMTipoEvento.LLUVIA) < MAX_LLUVIAS
                && azar.nextDouble() < probabilidadLluvia(ciudad.getProbabilidadLluvia())) {
            double radio = RADIO_LLUVIA_MIN + azar.nextDouble() * (RADIO_LLUVIA_MAX - RADIO_LLUVIA_MIN);
            nuevos.add(crear(EberthMTipoEvento.LLUVIA, minuto, 15 + azar.nextInt(26), radio));
        }
        return nuevos;
    }

    /** {@code true} si ya hay un policía activo en esa intersección. */
    public boolean hayPoliciaEn(double lon, double lat) {
        for (Activo a : activos) {
            EberthMEventoTrafico e = a.evento();
            if (e.tipo().esPolicia() && e.lon() == lon && e.lat() == lat) return true;
        }
        return false;
    }

    /** Despliega un policía en la intersección dada; caduca a los {@link #DURACION_POLICIA_MINUTOS} min. */
    public EberthMEventoTrafico crearPolicia(double lon, double lat, int minuto, double rumbo) {
        EberthMEventoTrafico evento = new EberthMEventoTrafico(
                EberthMTipoEvento.POLICIA_TRANSITO + " " + (++contador),
                EberthMTipoEvento.POLICIA_TRANSITO, lon, lat, 0, rumbo);
        activos.add(new Activo(evento, minuto + DURACION_POLICIA_MINUTOS));
        return evento;
    }

    private int cuantos(EberthMTipoEvento tipo) {
        int n = 0;
        for (Activo a : activos) if (a.evento().tipo() == tipo) n++;
        return n;
    }

    private EberthMEventoTrafico crear(EberthMTipoEvento tipo, int minuto, int duracion, double radio) {
        PieroGEdge via = vias.get(azar.nextInt(vias.size()));
        double lon = (via.origen.lon + via.destino.lon) / 2;
        double lat = (via.origen.lat + via.destino.lat) / 2;
        String nombre = tipo + " automática " + (++contador)
                + (via.name != null && !via.name.isBlank() ? " · " + via.name : "");
        EberthMEventoTrafico evento = new EberthMEventoTrafico(nombre, tipo, lon, lat, radio);
        activos.add(new Activo(evento, minuto + duracion));
        return evento;
    }
}
