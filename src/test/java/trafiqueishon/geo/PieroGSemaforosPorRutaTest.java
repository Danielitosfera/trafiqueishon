package trafiqueishon.geo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PieroGSemaforosPorRutaTest {

    private static final double LON = -70.0, LAT = -15.5, D = 0.005;

    private static PieroGVia via(double[]... coords) {
        PieroGVia via = new PieroGVia();
        via.highway = "residential";
        via.coords = new ArrayList<>(List.of(coords));
        return via;
    }

    /** Dos calles en cruz que se tocan en (LON, LAT). */
    private static PieroGGrafoVial cruz() {
        List<PieroGVia> vias = List.of(
                via(new double[]{LON - D, LAT}, new double[]{LON, LAT}, new double[]{LON + D, LAT}),
                via(new double[]{LON, LAT - D}, new double[]{LON, LAT}, new double[]{LON, LAT + D}));
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(vias);
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(vias, proyector);
        return grafo;
    }

    private static List<PieroGEdge> ruta(PieroGGrafoVial grafo, boolean horizontal) {
        return grafo.getEdges().stream()
                .filter(e -> horizontal ? e.origen.lat == e.destino.lat : e.origen.lon == e.destino.lon)
                .toList();
    }

    @Test
    void sinRutasNoHaySemaforos() {
        PieroGGrafoVial grafo = cruz();
        assertTrue(grafo.getNodosConSemaforo().isEmpty());
        grafo.regenerarSemaforos(List.of());
        assertTrue(grafo.getNodosConSemaforo().isEmpty());
    }

    @Test
    void unaSolaRutaNoCreaSemaforos() {
        PieroGGrafoVial grafo = cruz();
        grafo.regenerarSemaforos(List.of(ruta(grafo, true)));
        assertTrue(grafo.getNodosConSemaforo().isEmpty());
    }

    @Test
    void dosRutasQueSeCruzanTienenSemaforoSoloEnElCruce() {
        PieroGGrafoVial grafo = cruz();
        grafo.regenerarSemaforos(List.of(ruta(grafo, true), ruta(grafo, false)));
        List<PieroGNodo> con = grafo.getNodosConSemaforo();
        assertEquals(1, con.size());
        assertEquals(LON, con.getFirst().lon, 1e-9);
        assertEquals(LAT, con.getFirst().lat, 1e-9);
        assertNotNull(con.getFirst().semaforo);
    }

    @Test
    void alQuitarUnaRutaDesapareceElSemaforo() {
        PieroGGrafoVial grafo = cruz();
        grafo.regenerarSemaforos(List.of(ruta(grafo, true), ruta(grafo, false)));
        PieroGNodo cruce = grafo.getNodosConSemaforo().getFirst();
        grafo.regenerarSemaforos(List.of(ruta(grafo, true)));
        assertTrue(grafo.getNodosConSemaforo().isEmpty());
        assertNull(cruce.semaforo);
    }
}
