package trafiqueishon.simulacion;

/**
 * Semáforo de una intersección. El estado no se almacena: se deriva del reloj de la
 * simulación y de un desfase propio, así que "actualizarlo" es solo avanzar el reloj y
 * no hay que recorrer millones de nodos en cada paso.
 */
public final class EberthMSemaforo {
    public enum Estado { VERDE, AMARILLO, ROJO }

    public static final double VERDE_SEGUNDOS = 15.0;
    public static final double AMARILLO_SEGUNDOS = 3.0;
    public static final double ROJO_SEGUNDOS = 15.0;
    public static final double CICLO_SEGUNDOS = VERDE_SEGUNDOS + AMARILLO_SEGUNDOS + ROJO_SEGUNDOS;

    private final float desfaseSegundos;

    public EberthMSemaforo(int semilla) {
        // Desfase determinista por nodo para que las intersecciones no estén sincronizadas.
        int h = semilla * 0x9E3779B1;
        h ^= h >>> 15;
        this.desfaseSegundos = (float) (Math.floorMod(h, 1000) / 1000.0 * CICLO_SEGUNDOS);
    }

    public Estado estadoEn(double segundosSimulados) {
        double t = (segundosSimulados + desfaseSegundos) % CICLO_SEGUNDOS;
        if (t < VERDE_SEGUNDOS) return Estado.VERDE;
        if (t < VERDE_SEGUNDOS + AMARILLO_SEGUNDOS) return Estado.AMARILLO;
        return Estado.ROJO;
    }

    public boolean esRojo(double segundosSimulados) {
        return estadoEn(segundosSimulados) == Estado.ROJO;
    }
}
