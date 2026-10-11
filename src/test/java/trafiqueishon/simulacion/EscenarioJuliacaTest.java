package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;
import trafiqueishon.geo.GrafoVial;
import trafiqueishon.geo.GeoJsonParser;
import trafiqueishon.geo.Proyector;
import trafiqueishon.geo.Via;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EscenarioJuliacaTest {

    @Test
    void bundledRoadNetworkSupportsTheConfiguredPoiScenario() throws Exception {
        List<Via> vias = new GeoJsonParser()
                .parsearDesdeRecurso("/mapas/juliaca.geojson");
        Proyector proyector = new Proyector();
        proyector.preProyectar(vias);
        GrafoVial graph = new GrafoVial();
        graph.construir(vias, proyector);
        vias.forEach(Via::liberarCoords);

        assertTrue(graph.numNodos() > 1_000);
        assertTrue(graph.numEdges() > 1_000);

        List<PuntoInteres> puntos = new CargadorPuntosInteres()
                .cargarRecurso("/datos/poi.json");
        assertTrue(puntos.stream().anyMatch(punto -> punto.id().equals("salida_cusco")));
        assertTrue(puntos.stream().anyMatch(punto -> punto.id().equals("salida_puno")));
        assertTrue(puntos.stream().anyMatch(punto -> punto.id().equals("salida_huancane")));
        Set<Integer> nodosEscenario = new HashSet<>();
        for (PuntoInteres punto : puntos) {
            var nodo = graph.nodoMasCercanoLonLat(punto.lon(), punto.lat());
            assertNotNull(nodo, punto.nombre());
            assertTrue(nodosEscenario.add(nodo.id),
                    () -> "POI sin nodo vial diferenciado: " + punto.nombre());
        }
        SimuladorTrafico simulator = new SimuladorTrafico(graph, puntos);
        simulator.configurarDemanda(List.of(
                new DemandaVehicular(TipoVehiculo.TRAILER, 1)));
        simulator.iniciar(1);

        assertTrue(simulator.isEjecutando());
        assertTrue(simulator.getCantidadAccesosSpawner(TipoVehiculo.TRAILER) > 1);
        assertEquals(1, simulator.getFrame().cantidad());
        assertTrue(simulator.getEstadisticas().velocidadMediaKmH() > 0);
    }
}
