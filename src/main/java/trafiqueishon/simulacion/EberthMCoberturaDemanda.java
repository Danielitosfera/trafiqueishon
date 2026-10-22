package trafiqueishon.simulacion;

import trafiqueishon.geo.PieroGEdge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cuánta de la demanda de la ciudad queda cubierta por un conjunto de rutas. Un viaje zona → POI
 * está cubierto por una ruta si la zona queda a ≤ {@link #RADIO_ZONA_METROS} del trazado (se
 * camina hasta la parada) y el POI a ≤ 400 m del trazado (el pasajero baja y camina el resto).
 */
public final class EberthMCoberturaDemanda {
    private EberthMCoberturaDemanda() {}

    public static final double RADIO_ZONA_METROS = 450;
    public static final double RADIO_POI_METROS = EberthMIndicePoi.RADIO_CAMINATA_METROS;
    /** Bajo este porcentaje de una zona cubierta se avisa de que faltan rutas. */
    public static final double COBERTURA_MINIMA = 0.5;
    private static final int ZONAS_A_REVISAR = 5;

    public record Linea(String nombre, long pasajeros, String destinoPrincipal) {}

    public record Resultado(List<Linea> rutas, double porcentaje, List<String> advertencias) {
        public String texto() {
            StringBuilder sb = new StringBuilder();
            for (Linea l : rutas) {
                sb.append(String.format(Locale.ROOT, "%s cubre %,d pasajeros (%s).%n", l.nombre(),
                        l.pasajeros(), l.destinoPrincipal()));
            }
            sb.append(String.format(Locale.ROOT, "Cobertura total de la demanda: %.0f %%.", porcentaje * 100));
            for (String a : advertencias) sb.append("\n⚠ ").append(a);
            return sb.toString();
        }
    }

    /** {@code true} si el punto queda a ≤ {@code radio} metros de alguno de los tramos. */
    public static boolean cerca(double lon, double lat, List<PieroGEdge> tramos, double radio) {
        for (PieroGEdge e : tramos) {
            if (EberthMIndicePoi.distanciaPuntoSegmento(lon, lat, e.origen.lon, e.origen.lat,
                    e.destino.lon, e.destino.lat) <= radio) return true;
        }
        return false;
    }

    /** Zonas y POIs de la demanda que quedan junto al trazado: {zonaCerca[], poiCerca[]}. */
    public static boolean[][] alcance(EberthMDemandaCiudad demanda, List<PieroGEdge> tramos) {
        boolean[] zonas = new boolean[demanda.zonas().size()];
        boolean[] pois = new boolean[demanda.pois().size()];
        for (EberthMDemandaCiudad.Zona z : demanda.zonas()) {
            zonas[z.id()] = cerca(z.lon(), z.lat(), tramos, RADIO_ZONA_METROS);
        }
        for (int p = 0; p < pois.length; p++) {
            EberthMPoi poi = demanda.pois().get(p);
            pois[p] = cerca(poi.lon(), poi.lat(), tramos, RADIO_POI_METROS);
        }
        return new boolean[][]{zonas, pois};
    }

    /** Viajes diarios zona → POI que sirve un trazado. */
    public static double viajesServidos(EberthMDemandaCiudad demanda, boolean[][] alcance) {
        double suma = 0;
        for (EberthMDemandaCiudad.Zona z : demanda.zonas()) {
            if (!alcance[0][z.id()]) continue;
            for (EberthMDemandaCiudad.Flujo f : demanda.destinosDesdeZona(z.id())) {
                if (alcance[1][f.poi()]) suma += f.viajes();
            }
        }
        return suma;
    }

    /**
     * Porcentaje de la demanda cubierto por {@code base} y, después, por cada ruta de {@code nuevas}
     * añadida en orden: {@code [0]} = solo {@code base}; {@code [k]} = tras las primeras {@code k}.
     * Calcula el alcance de cada ruta una sola vez.
     */
    public static double[] coberturaAcumulada(EberthMDemandaCiudad demanda,
                                              List<EberthMServicioMicro> base,
                                              List<EberthMServicioMicro> nuevas) {
        int nz = demanda.zonas().size();
        boolean[][] cubierto = new boolean[nz][];
        double[] zonaTotal = new double[nz];
        double total = 0;
        for (int z = 0; z < nz; z++) {
            var flujos = demanda.destinosDesdeZona(z);
            cubierto[z] = new boolean[flujos.size()];
            for (var f : flujos) zonaTotal[z] += f.viajes();
            total += zonaTotal[z];
        }
        double[] resultado = new double[nuevas.size() + 1];
        double cubiertoSuma = 0;
        for (int paso = 0; paso <= nuevas.size(); paso++) {
            List<EberthMServicioMicro> aplicar = paso == 0 ? base : List.of(nuevas.get(paso - 1));
            for (EberthMServicioMicro s : aplicar) {
                boolean[][] a = alcance(demanda, s.trayecto().edges());
                for (int z = 0; z < nz; z++) {
                    if (!a[0][z]) continue;
                    var flujos = demanda.destinosDesdeZona(z);
                    for (int i = 0; i < flujos.size(); i++) {
                        if (!cubierto[z][i] && a[1][flujos.get(i).poi()]) {
                            cubierto[z][i] = true;
                            cubiertoSuma += flujos.get(i).viajes();
                        }
                    }
                }
            }
            resultado[paso] = total <= 0 ? 0 : cubiertoSuma / total;
        }
        return resultado;
    }

    public static Resultado evaluar(EberthMDemandaCiudad demanda, List<EberthMServicioMicro> servicios) {
        int nz = demanda.zonas().size();
        List<boolean[][]> alcances = new ArrayList<>();
        for (EberthMServicioMicro s : servicios) alcances.add(alcance(demanda, s.trayecto().edges()));

        List<Linea> lineas = new ArrayList<>();
        for (int r = 0; r < servicios.size(); r++) {
            boolean[][] a = alcances.get(r);
            double[] porPoi = new double[a[1].length];
            double total = 0;
            for (EberthMDemandaCiudad.Zona z : demanda.zonas()) {
                if (!a[0][z.id()]) continue;
                for (EberthMDemandaCiudad.Flujo f : demanda.destinosDesdeZona(z.id())) {
                    if (!a[1][f.poi()]) continue;
                    porPoi[f.poi()] += f.viajes();
                    total += f.viajes();
                }
            }
            int mejor = 0;
            for (int p = 1; p < porPoi.length; p++) if (porPoi[p] > porPoi[mejor]) mejor = p;
            lineas.add(new Linea(servicios.get(r).nombre(), Math.round(total),
                    total > 0 ? demanda.pois().get(mejor).nombre() : "sin destinos populares"));
        }

        double cubiertoTotal = 0, demandaTotal = 0;
        List<double[]> zonasDeficit = new ArrayList<>(); // {zona, demanda, cobertura}
        for (int z = 0; z < nz; z++) {
            double zonaTotal = 0, zonaCubierta = 0;
            for (EberthMDemandaCiudad.Flujo f : demanda.destinosDesdeZona(z)) {
                zonaTotal += f.viajes();
                for (boolean[][] a : alcances) {
                    if (a[0][z] && a[1][f.poi()]) {
                        zonaCubierta += f.viajes();
                        break;
                    }
                }
            }
            demandaTotal += zonaTotal;
            cubiertoTotal += zonaCubierta;
            if (zonaTotal > 0 && zonaCubierta / zonaTotal < COBERTURA_MINIMA) {
                zonasDeficit.add(new double[]{z, zonaTotal, zonaCubierta / zonaTotal});
            }
        }
        double porcentaje = demandaTotal <= 0 ? 0 : cubiertoTotal / demandaTotal;
        zonasDeficit.sort((x, y) -> Double.compare(y[1], x[1]));
        List<String> avisos = new ArrayList<>();
        for (int i = 0; i < Math.min(ZONAS_A_REVISAR, zonasDeficit.size()); i++) {
            double[] d = zonasDeficit.get(i);
            avisos.add(String.format(Locale.ROOT, "Faltan rutas para cubrir la demanda de %s (%.0f %% cubierta).",
                    demanda.zonas().get((int) d[0]).nombre(), d[2] * 100));
        }
        if (porcentaje < COBERTURA_MINIMA) {
            avisos.add(0, String.format(Locale.ROOT, "Cobertura global menor al 50 %%: faltan rutas "
                    + "(%d zonas con menos de la mitad de su demanda cubierta).", zonasDeficit.size()));
        }
        return new Resultado(lineas, porcentaje, avisos);
    }
}
