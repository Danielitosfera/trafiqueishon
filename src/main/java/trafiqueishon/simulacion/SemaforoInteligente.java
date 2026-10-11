package trafiqueishon.simulacion;

import trafiqueishon.geo.Edge;
import trafiqueishon.geo.Nodo;

import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

/** Control Q-learning acotado para una interseccion de dos ejes de aproximacion. */
public final class SemaforoInteligente {
    public enum ColorLuz { VERDE, AMARILLO, ROJO }

    private static final double T_MIN = 8;
    private static final double T_MAX = 45;
    private static final double[] DURACIONES = {12, 25, 40};
    private final Nodo nodo;
    private final double[][] q = new double[9][DURACIONES.length];
    private final Random random;
    private int fase;
    private int estadoAnterior = -1;
    private int accion = 1;
    private double tiempoFase;
    private double colaEjeA;
    private double colaEjeB;
    private double colaInicioFaseA;
    private double colaInicioFaseB;
    private boolean aprendizajeActivo = true;

    public SemaforoInteligente(Nodo nodo, long semilla) {
        this.nodo = Objects.requireNonNull(nodo);
        random = new Random(semilla);
    }

    public void setAprendizajeActivo(boolean activo) { aprendizajeActivo = activo; }

    public void reiniciarCiclo() {
        fase = 0;
        accion = 1;
        tiempoFase = 0;
        estadoAnterior = -1;
        colaEjeA = 0;
        colaEjeB = 0;
        colaInicioFaseA = 0;
        colaInicioFaseB = 0;
    }

    public void actualizar(double segundos, double colaA, double colaB) {
        if (!Double.isFinite(segundos) || segundos < 0
                || !Double.isFinite(colaA) || !Double.isFinite(colaB)
                || colaA < 0 || colaB < 0) {
            throw new IllegalArgumentException("Estado de semaforo invalido.");
        }
        colaEjeA = colaA;
        colaEjeB = colaB;
        tiempoFase += segundos;
        double duracionAnterior = duracionVerde();
        if (tiempoFase < duracionAnterior) return;

        int estado = discretizar(colaA - colaB);
        double recompensa = fase == 0
                ? colaInicioFaseA - colaA : colaInicioFaseB - colaB;
        if (aprendizajeActivo && estadoAnterior >= 0) {
            double maximo = Arrays.stream(q[estado]).max().orElse(0);
            q[estadoAnterior][accion] += 0.15
                    * (recompensa + 0.85 * maximo - q[estadoAnterior][accion]);
        }
        estadoAnterior = estado;
        fase = 1 - fase;
        accion = elegirAccion(estado);
        tiempoFase = Math.max(0, tiempoFase - duracionAnterior);
        colaInicioFaseA = colaA;
        colaInicioFaseB = colaB;
    }

    private int discretizar(double diferenciaCola) {
        return diferenciaCola < -5 ? 0 : diferenciaCola < 5 ? 1 : 2;
    }

    private int elegirAccion(int estado) {
        if (!aprendizajeActivo || random.nextDouble() < 0.08) {
            return random.nextInt(DURACIONES.length);
        }
        int mejor = 0;
        for (int i = 1; i < DURACIONES.length; i++) {
            if (q[estado][i] > q[estado][mejor]) mejor = i;
        }
        return mejor;
    }

    public boolean permite(Edge arista) {
        boolean ejeA = Math.abs(arista.origen.x - nodo.x)
                >= Math.abs(arista.origen.y - nodo.y);
        return fase == (ejeA ? 0 : 1);
    }

    public ColorLuz color(Edge arista) {
        if (tiempoFase >= duracionVerde() - 3) return ColorLuz.AMARILLO;
        return permite(arista) ? ColorLuz.VERDE : ColorLuz.ROJO;
    }

    public ColorLuz colorParaEje(boolean ejeA) {
        if (tiempoFase >= duracionVerde() - 3) return ColorLuz.AMARILLO;
        return fase == (ejeA ? 0 : 1) ? ColorLuz.VERDE : ColorLuz.ROJO;
    }

    public Nodo nodo() { return nodo; }
    public double colaEjeA() { return colaEjeA; }
    public double colaEjeB() { return colaEjeB; }
    public int fase() { return fase; }
    public double duracionVerde() { return limitarVerde(DURACIONES[accion]); }

    public static double limitarVerde(double segundos) {
        return Math.clamp(segundos, T_MIN, T_MAX);
    }
}
