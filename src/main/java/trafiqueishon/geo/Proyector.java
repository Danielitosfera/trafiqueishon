package trafiqueishon.geo;

import java.util.List;

/**
 * Pre-proyecta todas las vias a coordenadas normalizadas 0..1.
 * Se ejecuta UNA SOLA VEZ al cargar el GeoJSON.
 * Despues de esto, las vias NO guardan lon/lat, solo projX/projY.
 */
public class Proyector {

    private double minLon = Double.MAX_VALUE;
    private double maxLon = -Double.MAX_VALUE;
    private double minLat = Double.MAX_VALUE;
    private double maxLat = -Double.MAX_VALUE;

    private double anchoGeo = 1;
    private double altoGeo  = 1;

    public void preProyectar(List<Via> vias) {
        // 1. Bounding box global
        for (Via v : vias) {
            if (v.coords == null) continue;
            for (double[] c : v.coords) {
                if (c[0] < minLon) minLon = c[0];
                if (c[0] > maxLon) maxLon = c[0];
                if (c[1] < minLat) minLat = c[1];
                if (c[1] > maxLat) maxLat = c[1];
            }
        }
        anchoGeo = maxLon - minLon;
        altoGeo  = maxLat - minLat;
        if (anchoGeo <= 0) anchoGeo = 1;
        if (altoGeo  <= 0) altoGeo  = 1;

        // 2. Pre-proyectar y liberar coords
        for (Via v : vias) {
            if (v.coords == null || v.coords.isEmpty()) {
                v.projX = new double[0];
                v.projY = new double[0];
                continue;
            }

            int n = v.coords.size();
            v.projX = new double[n];
            v.projY = new double[n];

            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

            for (int i = 0; i < n; i++) {
                double[] c = v.coords.get(i);
                double nx = (c[0] - minLon) / anchoGeo;
                double ny = (maxLat - c[1]) / altoGeo;
                v.projX[i] = nx;
                v.projY[i] = ny;

                if (nx < minX) minX = nx;
                if (nx > maxX) maxX = nx;
                if (ny < minY) minY = ny;
                if (ny > maxY) maxY = ny;
            }
            v.minX = minX; v.maxX = maxX;
            v.minY = minY; v.maxY = maxY;

            // ANTES:
            //v.liberarCoords();

// DESPUÉS:
//v.liberarCoords();  // Se libera tras construir el grafo (Fase 3)
        }
    }

    public double getMinLon() { return minLon; }
    public double getMaxLon() { return maxLon; }
    public double getMinLat() { return minLat; }
    public double getMaxLat() { return maxLat; }
    public double getAnchoGeo() { return anchoGeo; }
    public double getAltoGeo() { return altoGeo; }
}