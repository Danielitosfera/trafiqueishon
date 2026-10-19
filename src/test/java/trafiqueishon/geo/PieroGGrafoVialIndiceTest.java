package trafiqueishon.geo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PieroGGrafoVialIndiceTest {

    @Test
    void laRejillaDevuelveElMismoNodoQueLaFuerzaBruta() {
        Random azar = new Random(7);
        List<PieroGVia> vias = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            double lon = -70.2 + azar.nextDouble() * 0.1, lat = -15.55 + azar.nextDouble() * 0.1;
            List<double[]> coords = new ArrayList<>();
            for (int k = 0; k < 6; k++) {
                coords.add(new double[]{lon, lat});
                lon += (azar.nextDouble() - 0.5) * 0.002;
                lat += (azar.nextDouble() - 0.5) * 0.002;
            }
            PieroGVia via = new PieroGVia();
            via.highway = "residential";
            via.coords = coords;
            vias.add(via);
        }
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(vias);
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(vias, proyector);
        assertTrue(grafo.numNodos() > 1000);

        for (int q = 0; q < 500; q++) {
            double nx = azar.nextDouble() * 1.2 - 0.1, ny = azar.nextDouble() * 1.2 - 0.1;
            PieroGNodo esperado = null;
            double mejor = Double.MAX_VALUE;
            for (PieroGNodo n : grafo.getNodos()) {
                double d = (n.x - nx) * (n.x - nx) + (n.y - ny) * (n.y - ny);
                if (d < mejor) { mejor = d; esperado = n; }
            }
            PieroGNodo obtenido = grafo.nodoMasCercano(nx, ny);
            double dObtenido = (obtenido.x - nx) * (obtenido.x - nx) + (obtenido.y - ny) * (obtenido.y - ny);
            assertEquals(mejor, dObtenido, 1e-15, "consulta " + q);
        }
    }

    @Test
    void losVerticesCompartidosSeUnenEnUnSoloNodo() {
        PieroGVia a = new PieroGVia();
        a.highway = "residential";
        a.coords = List.of(new double[]{-70.0, -15.0}, new double[]{-69.999, -15.0});
        PieroGVia b = new PieroGVia();
        b.highway = "residential";
        b.coords = List.of(new double[]{-69.999, -15.0}, new double[]{-69.999, -15.001});
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(List.of(a, b));
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(List.of(a, b), proyector);
        assertEquals(3, grafo.numNodos());
        assertEquals(4, grafo.numEdges());
        PieroGNodo centro = grafo.nodoMasCercanoLonLat(-69.999, -15.0);
        assertEquals(2, centro.salientes.size());
        assertNull(grafo.nodoMasCercanoLonLat(-70, -15, edge -> false));
    }
}
