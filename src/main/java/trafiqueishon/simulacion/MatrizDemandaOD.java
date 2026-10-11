package trafiqueishon.simulacion;

import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Matriz OD ponderada derivada de POI agrupados por k-means. Los pesos son
 * parametros de escenario, no aforos observados.
 */
public final class MatrizDemandaOD {
    private final List<PuntoInteres> puntos;
    private final double[][] pesos;
    private final double[] pesosOrigen;

    public MatrizDemandaOD(List<PuntoInteres> puntos, int cantidadZonas) {
        Objects.requireNonNull(puntos);
        if (puntos.size() < 2 || cantidadZonas < 1) {
            throw new IllegalArgumentException("Se requieren dos POI y al menos una zona.");
        }
        this.puntos = List.copyOf(puntos);
        int k = Math.min(cantidadZonas, puntos.size());
        double[][] centroides = new double[k][2];
        for (int i = 0; i < k; i++) {
            PuntoInteres p = puntos.get(i);
            centroides[i][0] = p.lon();
            centroides[i][1] = p.lat();
        }
        int[] asignacion = new int[puntos.size()];
        for (int iteracion = 0; iteracion < 30; iteracion++) {
            int[] conteos = new int[k];
            double[][] sumas = new double[k][2];
            for (int i = 0; i < puntos.size(); i++) {
                PuntoInteres punto = puntos.get(i);
                int mejor = 0;
                double mejorDistancia = Double.POSITIVE_INFINITY;
                for (int c = 0; c < k; c++) {
                    double dx = punto.lon() - centroides[c][0];
                    double dy = punto.lat() - centroides[c][1];
                    double distancia = dx * dx + dy * dy;
                    if (distancia < mejorDistancia) {
                        mejor = c;
                        mejorDistancia = distancia;
                    }
                }
                asignacion[i] = mejor;
                conteos[mejor]++;
                sumas[mejor][0] += punto.lon();
                sumas[mejor][1] += punto.lat();
            }
            for (int c = 0; c < k; c++) {
                if (conteos[c] > 0) {
                    centroides[c][0] = sumas[c][0] / conteos[c];
                    centroides[c][1] = sumas[c][1] / conteos[c];
                }
            }
        }

        double[] poblacion = new double[k];
        int[] miembros = new int[k];
        for (int i = 0; i < puntos.size(); i++) {
            poblacion[asignacion[i]] += puntos.get(i).peso();
            miembros[asignacion[i]]++;
        }
        pesosOrigen = new double[puntos.size()];
        double poblacionTotal = 0;
        for (double peso : poblacion) poblacionTotal += peso;
        for (int i = 0; i < puntos.size(); i++) {
            pesosOrigen[i] = poblacion[asignacion[i]]
                    / miembros[asignacion[i]] / poblacionTotal;
        }
        pesos = new double[puntos.size()][puntos.size()];
        for (int origen = 0; origen < puntos.size(); origen++) {
            double suma = 0;
            for (int destino = 0; destino < puntos.size(); destino++) {
                if (origen == destino) continue;
                double atraccion = puntos.get(destino).peso();
                pesos[origen][destino] = poblacion[asignacion[origen]] * atraccion;
                suma += pesos[origen][destino];
            }
            if (suma > 0) {
                for (int destino = 0; destino < puntos.size(); destino++) {
                    pesos[origen][destino] /= suma;
                }
            }
        }
    }

    public PuntoInteres elegirDestino(String origenId, Random random) {
        Objects.requireNonNull(random);
        int origen = indice(origenId);
        double valor = random.nextDouble();
        for (int destino = 0; destino < puntos.size(); destino++) {
            valor -= pesos[origen][destino];
            if (valor <= 0 && destino != origen) return puntos.get(destino);
        }
        for (int destino = puntos.size() - 1; destino >= 0; destino--) {
            if (destino != origen) return puntos.get(destino);
        }
        throw new IllegalStateException("La matriz OD no contiene destinos.");
    }

    public PuntoInteres elegirOrigen(Random random) {
        Objects.requireNonNull(random);
        double valor = random.nextDouble();
        for (int i = 0; i < puntos.size(); i++) {
            valor -= pesosOrigen[i];
            if (valor <= 0) return puntos.get(i);
        }
        return puntos.get(puntos.size() - 1);
    }

    public double probabilidad(String origenId, String destinoId) {
        return pesos[indice(origenId)][indice(destinoId)];
    }

    public double probabilidadOrigen(String origenId) {
        return pesosOrigen[indice(origenId)];
    }

    private int indice(String id) {
        for (int i = 0; i < puntos.size(); i++) {
            if (puntos.get(i).id().equals(id)) return i;
        }
        throw new IllegalArgumentException("POI desconocido: " + id);
    }
}
