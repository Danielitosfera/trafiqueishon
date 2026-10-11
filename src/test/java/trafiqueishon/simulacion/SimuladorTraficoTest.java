package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;
import trafiqueishon.geo.GrafoVial;
import trafiqueishon.geo.Proyector;
import trafiqueishon.geo.Via;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SimuladorTraficoTest {

    @Test
    void vehiclesMoveCompleteTripsAndCreateCongestion() {
        GrafoVial graph = buildGraph(List.of(
                via(new double[]{0, 0}, new double[]{0.001, 0}),
                via(new double[]{0.001, 0}, new double[]{0.002, 0})
        ));
        SimuladorTrafico simulator = new SimuladorTrafico(graph, List.of(
                new PuntoInteres("west", "Oeste", 0, 0),
                new PuntoInteres("east", "Este", 0.002, 0)
        ));
        simulator.setEscalaTiempo(20);
        simulator.iniciar(20);

        for (int i = 0; i < 10; i++) simulator.actualizar(2);

        EstadisticasSimulacion stats = simulator.getEstadisticas();
        assertTrue(simulator.isEjecutando());
        assertEquals(20, stats.vehiculosActivos());
        assertTrue(stats.viajesCompletados() > 0);
        assertTrue(stats.tramosCongestionados() > 0);
        assertEquals(20, simulator.getFrame().cantidad());

        simulator.pausar();
        double tiempoPausado = simulator.getEstadisticas().tiempoSimuladoSegundos();
        simulator.actualizar(10);
        assertEquals(tiempoPausado, simulator.getEstadisticas().tiempoSimuladoSegundos());

        simulator.reiniciar();
        assertEquals(0, simulator.getEstadisticas().vehiculosActivos());
        assertEquals(0, simulator.getEstadisticas().viajesCompletados());
        assertEquals(0, simulator.getFrame().cantidad());
    }

    @Test
    void supportsAtLeastEightThousandConcurrentAgentsWithCompactSnapshots() {
        GrafoVial graph = buildGraph(List.of(
                via(new double[]{0, 0}, new double[]{0.001, 0}),
                via(new double[]{0.001, 0}, new double[]{0.002, 0})
        ));
        SimuladorTrafico simulator = new SimuladorTrafico(graph, List.of(
                new PuntoInteres("west", "Oeste", 0, 0),
                new PuntoInteres("east", "Este", 0.002, 0)
        ));

        simulator.iniciar(8_500);
        SimuladorTrafico.FrameVehiculos frame = simulator.getFrame();
        for (int tick = 0; tick < 10; tick++) simulator.actualizar(0.25);

        assertEquals(8_500, frame.cantidad());
        assertEquals(8_500, simulator.getFrame().cantidad());
        assertEquals(8_500, simulator.getEstadisticas().vehiculosActivos());
    }

    @Test
    void spawnerBuildsRoutesFromMultipleRoadAccessNodes() {
        GrafoVial graph = buildGraph(List.of(
                via(new double[]{0, 0}, new double[]{0.001, 0}),
                via(new double[]{0.001, 0}, new double[]{0.002, 0})
        ));
        SimuladorTrafico simulator = new SimuladorTrafico(graph, List.of(
                new PuntoInteres("west", "Oeste", 0, 0),
                new PuntoInteres("east", "Este", 0.002, 0)
        ));

        simulator.iniciar(1);

        assertTrue(simulator.getCantidadAccesosSpawner(TipoVehiculo.PARTICULAR) >= 2);
    }

    @Test
    void configuredMicroRouteDispatchesUnitsAndDemandSpawnsVehicleTypes() {
        GrafoVial graph = buildGraph(List.of(
                via(new double[]{0, 0}, new double[]{0.001, 0}),
                via(new double[]{0.001, 0}, new double[]{0.002, 0})
        ));
        SimuladorTrafico simulator = new SimuladorTrafico(graph, List.of(
                new PuntoInteres("west", "Oeste", 0, 0),
                new PuntoInteres("east", "Este", 0.002, 0)
        ));
        simulator.setLimiteVehiculos(100);
        simulator.configurarDemanda(List.of(
                new DemandaVehicular(TipoVehiculo.MOTO_LINEAL, 120)));
        simulator.configurarMicros(List.of(
                new RutaMicro("M1", "Transportes Sol",
                        List.of("west", "east"), 1, 2, "")));
        simulator.setEscalaTiempo(20);
        simulator.iniciar(0);
        simulator.actualizar(0.25);

        SimuladorTrafico.FrameVehiculos frame = simulator.getFrame();
        assertTrue(frame.cantidad() >= 1);
        assertTrue(java.util.stream.IntStream.range(0, frame.cantidad())
                .anyMatch(i -> frame.tipos()[i] == (byte) TipoVehiculo.MICRO.ordinal()));
        assertTrue(java.util.stream.IntStream.range(0, frame.cantidad())
                .anyMatch(i -> frame.tipos()[i] == (byte) TipoVehiculo.MOTO_LINEAL.ordinal()));
        int micro = java.util.stream.IntStream.range(0, frame.cantidad())
                .filter(i -> frame.tipos()[i] == (byte) TipoVehiculo.MICRO.ordinal())
                .findFirst().orElseThrow();
        assertEquals("Transportes Sol", frame.nombresEmpresas()
                .get(frame.empresas()[micro]));
    }

    @Test
    void detectsMultiApproachJunctionsOnPriorityRoadsForSignals() {
        Via principal = via(new double[]{0, 0}, new double[]{0.001, 0});
        principal.highway = "primary";
        Via continuidad = via(new double[]{0.001, 0}, new double[]{0.002, 0});
        continuidad.highway = "primary";
        GrafoVial graph = buildGraph(List.of(principal,
                continuidad,
                via(new double[]{0.001, 0}, new double[]{0.001, 0.001}),
                via(new double[]{0.001, 0}, new double[]{0.001, -0.001})));
        SimuladorTrafico simulator = new SimuladorTrafico(graph, List.of(
                new PuntoInteres("west", "Oeste", 0, 0),
                new PuntoInteres("north", "Norte", 0.001, 0.001)));

        assertEquals(1, simulator.getSemaforos().size());
        int idNodo = simulator.getSemaforos().getFirst().nodo().id;
        assertEquals(4, simulator.getSemaforos().getFirst().nodo().salientes.stream()
                .map(edge -> edge.destino.id).distinct().count());
        assertTrue(simulator.quitarSemaforo(idNodo));
        assertFalse(simulator.tieneSemaforo(idNodo));
        assertEquals(1, simulator.colocarSemaforosSugeridos());
        assertTrue(simulator.tieneSemaforo(idNodo));
    }

    @Test
    void microResumesAfterOrderedIntermediateStop() {
        GrafoVial graph = buildGraph(List.of(
                via(new double[]{0, 0}, new double[]{0.001, 0}),
                via(new double[]{0.001, 0}, new double[]{0.002, 0})));
        SimuladorTrafico simulator = new SimuladorTrafico(graph, List.of(
                new PuntoInteres("west", "Oeste", 0, 0),
                new PuntoInteres("middle", "Centro", 0.001, 0),
                new PuntoInteres("east", "Este", 0.002, 0)));
        simulator.setLimiteVehiculos(10);
        simulator.configurarMicros(List.of(
                new RutaMicro("M1", List.of("west", "middle", "east"), 1, 1)));
        simulator.setEscalaTiempo(20);
        simulator.iniciar(0);
        for (int i = 0; i < 100; i++) simulator.actualizar(0.25);

        assertTrue(simulator.getEstadisticas().viajesCompletados() > 0);
    }

    private static Via via(double[]... coordinates) {
        Via via = new Via();
        via.highway = "residential";
        via.lanes = 1;
        via.maxspeed = 30;
        via.coords = List.of(coordinates);
        return via;
    }

    private static GrafoVial buildGraph(List<Via> vias) {
        Proyector proyector = new Proyector();
        proyector.preProyectar(vias);
        GrafoVial graph = new GrafoVial();
        graph.construir(vias, proyector);
        return graph;
    }
}
