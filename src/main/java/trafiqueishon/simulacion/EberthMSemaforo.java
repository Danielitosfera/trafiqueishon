package trafiqueishon.simulacion;

import trafiqueishon.geo.PieroGEdge;

/**
 * Semáforo de dos fases de una intersección: una para los accesos horizontales y otra para los
 * verticales. Reparte el ciclo con el método de Webster simplificado: la demanda de cada eje
 * (rutas de micros que llegan por él) fija su razón de flujo {@code y = demanda / saturación};
 * el ciclo óptimo es {@code C = (1,5·L + 5) / (1 − Y)} y el verde de cada fase es
 * {@code (C − L) · y / Y}. Un cruce cargado (muchas rutas) obtiene así un ciclo más largo y un
 * verde más largo para su eje principal. El estado no se almacena: se deriva del reloj de la
 * simulación y de un desfase propio, así que "actualizarlo" es solo avanzar el reloj y no hay
 * que recorrer millones de nodos en cada paso.
 *
 * <p>Webster simplificado: no se miden flujos de saturación ni giros; la saturación de un
 * acceso se aproxima con {@link #SATURACION_RUTAS}. En hora punta el ciclo se acorta un 25 %
 * con la misma proporción de verdes.</p>
 */
public final class EberthMSemaforo {
    public enum Estado { VERDE, AMARILLO, ROJO }

    /** Acceso a la intersección según la orientación del tramo que llega. */
    public enum Eje {
        HORIZONTAL, VERTICAL;

        public static Eje de(PieroGEdge edge) {
            return Math.abs(edge.destino.x - edge.origen.x) >= Math.abs(edge.destino.y - edge.origen.y)
                    ? HORIZONTAL : VERTICAL;
        }
    }

    /** Ciclo de una intersección sin demanda conocida. */
    public static final double CICLO_SEGUNDOS = 60.0;
    public static final double CICLO_MINIMO_SEGUNDOS = 40.0;
    public static final double CICLO_MAXIMO_SEGUNDOS = 90.0;
    /** En hora punta el ciclo dura esta fracción del normal. */
    public static final double FACTOR_CICLO_HORA_PUNTA = 0.75;
    public static final double AMARILLO_SEGUNDOS = 3.0;
    /** Tiempo perdido por ciclo: amarillo + todo-rojo (1 s) en cada una de las dos fases. */
    static final double PERDIDA_SEGUNDOS = 2 * (AMARILLO_SEGUNDOS + 1);
    /** Rutas por eje que saturan un acceso (razón de flujo = 1). */
    public static final double SATURACION_RUTAS = 16;
    /** Un solo eje nunca consume más de esta razón de flujo, para no dejar sin verde al otro. */
    static final double FLUJO_MAXIMO_EJE = 0.45;
    /** Verde mínimo de cualquier fase (cruce peatonal). */
    public static final double VERDE_MINIMO_SEGUNDOS = 10.0;
    /** A partir de esta velocidad de simulación los tiempos se alargan para poder verlos. */
    public static final double VELOCIDAD_CICLO_LARGO = 30.0;
    public static final double ESCALA_CICLO_LARGO = 2.0;

    private record Tiempos(double ciclo, double verdeHorizontal) {}

    private final double desfase; // fracción del ciclo, para que las intersecciones no estén sincronizadas
    private volatile int demandaHorizontal = 1;
    private volatile int demandaVertical = 1;
    private volatile boolean horaPunta;
    private volatile double escala = 1.0;
    private volatile Tiempos tiempos;

    public EberthMSemaforo(int semilla) {
        int h = semilla * 0x9E3779B1;
        h ^= h >>> 15;
        this.desfase = Math.floorMod(h, 1000) / 1000.0;
        recalcular();
    }

    /** Rutas que llegan por cada eje; el ciclo se recalcula al instante. */
    public void setDemanda(int horizontal, int vertical) {
        this.demandaHorizontal = Math.max(0, horizontal);
        this.demandaVertical = Math.max(0, vertical);
        recalcular();
    }

    public void setHoraPunta(boolean horaPunta) {
        this.horaPunta = horaPunta;
        recalcular();
    }

    /** Multiplica la duración de todo el ciclo (para velocidades de simulación altas). */
    public void setEscalaTiempo(double escala) {
        this.escala = Math.max(0.1, escala);
        recalcular();
    }

    private void recalcular() {
        double yH = Math.min(FLUJO_MAXIMO_EJE, demandaHorizontal / SATURACION_RUTAS);
        double yV = Math.min(FLUJO_MAXIMO_EJE, demandaVertical / SATURACION_RUTAS);
        double flujo = yH + yV;
        double ciclo = CICLO_SEGUNDOS;
        double verde = ciclo / 2;
        if (flujo > 0) {
            ciclo = Math.clamp((1.5 * PERDIDA_SEGUNDOS + 5) / (1 - flujo),
                    CICLO_MINIMO_SEGUNDOS, CICLO_MAXIMO_SEGUNDOS);
        }
        if (horaPunta) ciclo *= FACTOR_CICLO_HORA_PUNTA;
        if (flujo > 0) {
            double util = ciclo - PERDIDA_SEGUNDOS;
            verde = PERDIDA_SEGUNDOS / 2 + Math.clamp(util * yH / flujo,
                    VERDE_MINIMO_SEGUNDOS, util - VERDE_MINIMO_SEGUNDOS);
        } else if (horaPunta) {
            verde = ciclo / 2;
        }
        tiempos = new Tiempos(ciclo * escala, verde * escala);
    }

    public double cicloSegundos() { return tiempos.ciclo(); }

    /** Segundos de verde (incluido el amarillo final) del eje indicado. */
    public double verdeSegundos(Eje eje) {
        Tiempos t = tiempos;
        return eje == Eje.HORIZONTAL ? t.verdeHorizontal() : t.ciclo() - t.verdeHorizontal();
    }

    /**
     * @param policia con un policía de tránsito en el cruce la luz roja dura la mitad
     */
    public Estado estadoEn(double segundosSimulados, Eje eje, boolean policia) {
        Tiempos t = tiempos;
        double pos = (segundosSimulados + desfase * t.ciclo()) % t.ciclo();
        double verde = t.verdeHorizontal();
        if (eje == Eje.VERTICAL) {
            pos -= t.verdeHorizontal();
            if (pos < 0) pos += t.ciclo();
            verde = t.ciclo() - t.verdeHorizontal();
        }
        double amarillo = Math.min(AMARILLO_SEGUNDOS, verde / 3);
        if (pos < verde - amarillo) return Estado.VERDE;
        if (pos < verde) return Estado.AMARILLO;
        if (policia && pos - verde >= (t.ciclo() - verde) / 2) return Estado.VERDE;
        return Estado.ROJO;
    }

    public boolean esRojo(double segundosSimulados, Eje eje, boolean policia) {
        return estadoEn(segundosSimulados, eje, policia) == Estado.ROJO;
    }
}
