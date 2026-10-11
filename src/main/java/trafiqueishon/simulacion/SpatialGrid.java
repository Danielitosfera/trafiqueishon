package trafiqueishon.simulacion;

import java.util.function.IntConsumer;

/**
 * Rejilla espacial de arreglos primitivos para consultas de proximidad sin
 * crear una estructura por vehiculo en cada tick.
 */
public final class SpatialGrid {
    private final int columnas;
    private final int filas;
    private final int[] cabezas;
    private int[] siguiente = new int[0];
    private float[] x = new float[0];
    private float[] y = new float[0];
    private int cantidad;

    public SpatialGrid(int columnas, int filas) {
        if (columnas < 1 || filas < 1) {
            throw new IllegalArgumentException("La rejilla requiere dimensiones positivas.");
        }
        this.columnas = columnas;
        this.filas = filas;
        cabezas = new int[Math.multiplyExact(columnas, filas)];
    }

    public void reconstruir(float[] coordenadasX, float[] coordenadasY, int total) {
        if (total < 0 || total > coordenadasX.length || total > coordenadasY.length) {
            throw new IllegalArgumentException("Cantidad de agentes inconsistente.");
        }
        if (siguiente.length < total) {
            siguiente = new int[total];
            x = new float[total];
            y = new float[total];
        }
        java.util.Arrays.fill(cabezas, -1);
        cantidad = total;
        for (int i = 0; i < total; i++) {
            x[i] = coordenadasX[i];
            y[i] = coordenadasY[i];
            int celda = indiceCelda(x[i], y[i]);
            siguiente[i] = cabezas[celda];
            cabezas[celda] = i;
        }
    }

    public void paraCadaCercano(float centroX, float centroY, float radio,
                                IntConsumer consumidor) {
        if (!Float.isFinite(centroX) || !Float.isFinite(centroY)
                || !Float.isFinite(radio) || radio < 0 || consumidor == null) {
            throw new IllegalArgumentException("Consulta espacial invalida.");
        }
        int minX = celdaX(centroX - radio);
        int maxX = celdaX(centroX + radio);
        int minY = celdaY(centroY - radio);
        int maxY = celdaY(centroY + radio);
        float radio2 = radio * radio;
        for (int cy = minY; cy <= maxY; cy++) {
            for (int cx = minX; cx <= maxX; cx++) {
                for (int agente = cabezas[cy * columnas + cx];
                     agente >= 0; agente = siguiente[agente]) {
                    float dx = x[agente] - centroX;
                    float dy = y[agente] - centroY;
                    if (dx * dx + dy * dy <= radio2) consumidor.accept(agente);
                }
            }
        }
    }

    public void paraCadaParCercano(float radio, PairConsumer consumidor) {
        if (!Float.isFinite(radio) || radio < 0 || consumidor == null) {
            throw new IllegalArgumentException("Consulta de pares espaciales invalida.");
        }
        float radio2 = radio * radio;
        for (int agente = 0; agente < cantidad; agente++) {
            int minX = celdaX(x[agente] - radio);
            int maxX = celdaX(x[agente] + radio);
            int minY = celdaY(y[agente] - radio);
            int maxY = celdaY(y[agente] + radio);
            for (int fila = minY; fila <= maxY; fila++) {
                for (int columna = minX; columna <= maxX; columna++) {
                    for (int otro = cabezas[fila * columnas + columna];
                         otro >= 0; otro = siguiente[otro]) {
                        if (otro <= agente) continue;
                        float dx = x[otro] - x[agente];
                        float dy = y[otro] - y[agente];
                        if (dx * dx + dy * dy <= radio2) consumidor.accept(agente, otro);
                    }
                }
            }
        }
    }

    public int cantidad() { return cantidad; }

    @FunctionalInterface
    public interface PairConsumer {
        void accept(int primero, int segundo);
    }

    private int indiceCelda(float px, float py) {
        return celdaY(py) * columnas + celdaX(px);
    }

    private int celdaX(float px) {
        return Math.clamp((int) (px * columnas), 0, columnas - 1);
    }

    private int celdaY(float py) {
        return Math.clamp((int) (py * filas), 0, filas - 1);
    }
}
