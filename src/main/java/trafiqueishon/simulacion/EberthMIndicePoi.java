package trafiqueishon.simulacion;

import trafiqueishon.geo.PieroGEdge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rejilla espacial de POIs (celdas de {@link #RADIO_CAMINATA_METROS}). Permite hallar los POIs
 * cercanos a una ruta sin comparar cada tramo con todos los POIs del mapa.
 */
public final class EberthMIndicePoi {

    /** Distancia máxima de un POI destino a la ruta del micro (≈ 4 cuadras). */
    public static final double RADIO_CAMINATA_METROS = 400;
    /** Distancia a la que el micro "deja" a los pasajeros de un POI destino. */
    public static final double RADIO_BAJADA_METROS = 50;

    private static final double METROS_POR_GRADO = 111_320;
    public static final EberthMIndicePoi VACIO = new EberthMIndicePoi(List.of());

    /** POI alcanzable desde la ruta y primer tramo de la ruta desde el que está a distancia de caminata. */
    public record Candidato(EberthMPoi poi, int indiceEdge) {}

    private final Map<Long, List<EberthMPoi>> celdas = new HashMap<>();
    private final double celdaLat = RADIO_CAMINATA_METROS / METROS_POR_GRADO;
    private final double celdaLon;
    private final boolean vacio;

    public EberthMIndicePoi(List<EberthMPoi> pois) {
        vacio = pois.isEmpty();
        double latMedia = pois.stream().mapToDouble(EberthMPoi::lat).average().orElse(0);
        celdaLon = celdaLat / Math.max(0.1, Math.cos(Math.toRadians(latMedia)));
        for (EberthMPoi poi : pois) {
            celdas.computeIfAbsent(clave(celda(poi.lon(), celdaLon), celda(poi.lat(), celdaLat)),
                    k -> new ArrayList<>()).add(poi);
        }
    }

    public boolean estaVacio() {
        return vacio;
    }

    /** POIs a ≤ 400 m de la ruta, ordenados por el primer tramo desde el que son alcanzables. */
    public List<Candidato> candidatosEnRuta(List<PieroGEdge> ruta) {
        if (vacio) return List.of();
        Set<EberthMPoi> vistos = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Candidato> candidatos = new ArrayList<>();
        for (int i = 0; i < ruta.size(); i++) {
            PieroGEdge e = ruta.get(i);
            long x0 = celda(Math.min(e.origen.lon, e.destino.lon), celdaLon) - 1;
            long x1 = celda(Math.max(e.origen.lon, e.destino.lon), celdaLon) + 1;
            long y0 = celda(Math.min(e.origen.lat, e.destino.lat), celdaLat) - 1;
            long y1 = celda(Math.max(e.origen.lat, e.destino.lat), celdaLat) + 1;
            for (long cx = x0; cx <= x1; cx++) {
                for (long cy = y0; cy <= y1; cy++) {
                    List<EberthMPoi> enCelda = celdas.get(clave(cx, cy));
                    if (enCelda == null) continue;
                    for (EberthMPoi poi : enCelda) {
                        if (vistos.contains(poi)) continue;
                        if (distanciaPuntoSegmento(poi.lon(), poi.lat(), e.origen.lon, e.origen.lat,
                                e.destino.lon, e.destino.lat) <= RADIO_CAMINATA_METROS) {
                            vistos.add(poi);
                            candidatos.add(new Candidato(poi, i));
                        }
                    }
                }
            }
        }
        return candidatos;
    }

    /** POIs a no más de {@code radioMetros} (≤ {@link #RADIO_CAMINATA_METROS}) del punto. */
    public List<EberthMPoi> cercanos(double lon, double lat, double radioMetros) {
        if (vacio) return List.of();
        List<EberthMPoi> resultado = new ArrayList<>();
        long cx = celda(lon, celdaLon), cy = celda(lat, celdaLat);
        for (long x = cx - 1; x <= cx + 1; x++) {
            for (long y = cy - 1; y <= cy + 1; y++) {
                List<EberthMPoi> enCelda = celdas.get(clave(x, y));
                if (enCelda == null) continue;
                for (EberthMPoi poi : enCelda) {
                    if (distanciaPuntoSegmento(poi.lon(), poi.lat(), lon, lat, lon, lat) <= radioMetros) {
                        resultado.add(poi);
                    }
                }
            }
        }
        return resultado;
    }

    /** Distancia aproximada en metros entre un punto y el segmento A-B (proyección local). */
    public static double distanciaPuntoSegmento(double lon, double lat, double aLon, double aLat,
                                                double bLon, double bLat) {
        return proyectarEnSegmento(lon, lat, aLon, aLat, bLon, bLat)[0];
    }

    /**
     * Proyecta un punto sobre el segmento A-B (proyección local).
     *
     * @return {@code {distancia en metros, fracción t en [0,1] del punto más cercano}}
     */
    public static double[] proyectarEnSegmento(double lon, double lat, double aLon, double aLat,
                                               double bLon, double bLat) {
        double escalaX = METROS_POR_GRADO * Math.cos(Math.toRadians(lat));
        double ax = aLon * escalaX, ay = aLat * METROS_POR_GRADO;
        double dx = bLon * escalaX - ax, dy = bLat * METROS_POR_GRADO - ay;
        double px = lon * escalaX, py = lat * METROS_POR_GRADO;
        double l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.clamp(((px - ax) * dx + (py - ay) * dy) / l2, 0, 1);
        return new double[]{Math.hypot(px - (ax + t * dx), py - (ay + t * dy)), t};
    }

    private static long celda(double valor, double tamano) {
        return (long) Math.floor(valor / tamano);
    }

    private static long clave(long cx, long cy) {
        return (cx << 32) ^ (cy & 0xffffffffL);
    }
}
