package trafiqueishon.simulacion;

import java.util.List;
import java.util.Random;

/**
 * Demanda orgánica de pasajeros: cuántos viajes hay en cada hora del día (franjas horarias) y a
 * qué tipo de destino van (según la hora y la popularidad de cada POI). Son estimaciones
 * razonadas, no aforos medidos.
 */
public final class EberthMGeneradorDemanda {
    private EberthMGeneradorDemanda() {}

    public static final double PICO_MANANA = 0.35;   // 07:00-09:00
    public static final double MEDIODIA = 0.15;      // 12:00-14:00
    public static final double PICO_TARDE = 0.35;    // 17:00-20:00
    public static final double NOCHE = 0.15;         // resto del día

    public enum Franja { PICO_MANANA, MEDIODIA, PICO_TARDE, NOCHE }

    public static Franja franja(int hora) {
        int h = Math.floorMod(hora, 24);
        if (h >= 7 && h < 9) return Franja.PICO_MANANA;
        if (h >= 12 && h < 14) return Franja.MEDIODIA;
        if (h >= 17 && h < 20) return Franja.PICO_TARDE;
        return Franja.NOCHE;
    }

    /** Fracción de la demanda diaria que ocurre en esa hora (las 24 suman 1). */
    public static double fraccionHoraria(int hora) {
        return switch (franja(hora)) {
            case PICO_MANANA -> PICO_MANANA / 2;
            case MEDIODIA -> MEDIODIA / 2;
            case PICO_TARDE -> PICO_TARDE / 3;
            case NOCHE -> NOCHE / 17;
        };
    }

    /** Viajes proyectados en una hora para {@code usuariosDiarios} usuarios del transporte público. */
    public static double pasajerosHora(double usuariosDiarios, int hora) {
        return usuariosDiarios * fraccionHoraria(hora);
    }

    /** Relación de la hora con una hora «media» plana (1 = promedio); escala la generación por minuto. */
    public static double factorRelativo(int minutoDelDia) {
        return fraccionHoraria(Math.floorMod(minutoDelDia, 24 * 60) / 60) * 24;
    }

    // Peso del destino por categoría y franja. Orden: EDUCACION, COMERCIO, SALUD, TURISMO, TRABAJO, OTRO.
    private static final double[][] DESTINO = {
            {60, 12, 8, 2, 15, 3},   // pico mañana: las escuelas concentran el 60 %
            {20, 30, 15, 12, 15, 8}, // mediodía
            {10, 30, 12, 12, 28, 8}, // pico tarde
            {2, 25, 25, 25, 8, 15}}; // noche

    /** Porción (0-1) de los viajes de esa hora cuyo destino es la categoría. */
    public static double pesoCategoria(EberthMCategoriaPoi categoria, int hora) {
        double[] fila = DESTINO[franja(hora).ordinal()];
        double suma = 0;
        for (double p : fila) suma += p;
        return fila[categoria.ordinal()] / suma;
    }

    /**
     * Sortea el destino de un viaje: primero la categoría según la hora y después un POI de esa
     * categoría con probabilidad proporcional a su popularidad. Null si no hay POIs.
     */
    public static EberthMPoi sortearDestino(List<EberthMPoi> pois, int hora, Random azar) {
        if (pois.isEmpty()) return null;
        double total = 0;
        double[] pesos = new double[pois.size()];
        // El peso de la categoría se reparte entre sus POIs, así que una categoría con muchos
        // POIs no gana por cantidad: cada POI pesa categoría × popularidad / suma de popularidades.
        double[] sumaPopularidad = new double[EberthMCategoriaPoi.values().length];
        for (EberthMPoi p : pois) sumaPopularidad[p.categoria().ordinal()] += p.popularidad();
        for (int i = 0; i < pesos.length; i++) {
            EberthMPoi p = pois.get(i);
            double suma = sumaPopularidad[p.categoria().ordinal()];
            pesos[i] = suma <= 0 ? 0 : pesoCategoria(p.categoria(), hora) * p.popularidad() / suma;
            total += pesos[i];
        }
        if (total <= 0) return pois.get(azar.nextInt(pois.size()));
        double r = azar.nextDouble() * total;
        for (int i = 0; i < pesos.length; i++) {
            r -= pesos[i];
            if (r < 0) return pois.get(i);
        }
        return pois.getLast();
    }

    /**
     * Destino de un pasajero nacido en una zona residencial, según el flujo real zona → POI de
     * la demanda de la ciudad (popularidad del POI y cercanía). Null si la zona no tiene flujos.
     */
    public static EberthMPoi sortearDestinoPorFlujo(EberthMDemandaCiudad demanda, int zona, Random azar) {
        int poi = demanda.sortearPoi(zona, azar);
        return poi < 0 ? null : demanda.pois().get(poi);
    }
}
