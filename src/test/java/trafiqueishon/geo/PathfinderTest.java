package trafiqueishon.geo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import trafiqueishon.simulacion.TipoVehiculo;

public class PathfinderTest {

    @Test
    void usesMetricLengthsAndHonorsOneWayDirection() {
        GrafoVial graph = buildGraph(List.of(
                via("residential", true, false, 30,
                        new double[]{0, 0}, new double[]{0.001, 0})
        ));
        Nodo west = graph.nodoMasCercanoLonLat(0, 0);
        Nodo east = graph.nodoMasCercanoLonLat(0.001, 0);

        RutaResult forward = new Pathfinder(graph).ruta(west, east);

        assertNotNull(forward);
        assertEquals(111, forward.distanciaMetros(), 2);
        assertTrue(forward.tiempoSegundos() > 0);
        assertNull(new Pathfinder(graph).ruta(east, west));
    }

    @Test
    void reversesWaysTaggedAsNegativeOneWay() {
        GrafoVial graph = buildGraph(List.of(
                via("residential", true, true, 30,
                        new double[]{0, 0}, new double[]{0.001, 0})
        ));
        Nodo west = graph.nodoMasCercanoLonLat(0, 0);
        Nodo east = graph.nodoMasCercanoLonLat(0.001, 0);

        assertNotNull(new Pathfinder(graph).ruta(east, west));
        assertNull(new Pathfinder(graph).ruta(west, east));
    }

    @Test
    void heavyVehiclesAvoidResidentialRoadsWhenAnArterialRouteExists() {
        GrafoVial graph = buildGraph(List.of(
                via("residential", false, false, 30,
                        new double[]{0, 0}, new double[]{0.001, 0}),
                via("primary", false, false, 50,
                        new double[]{0, 0}, new double[]{0, 0.001}),
                via("primary", false, false, 50,
                        new double[]{0, 0.001}, new double[]{0.001, 0})
        ));
        Nodo start = graph.nodoMasCercanoLonLat(0, 0);
        Nodo end = graph.nodoMasCercanoLonLat(0.001, 0);

        RutaResult trailer = new Pathfinder(graph).ruta(
                start, end, edge -> TipoVehiculo.TRAILER.puedeCircular(edge.highway));

        assertNotNull(trailer);
        assertTrue(trailer.getEdges().stream()
                .allMatch(edge -> TipoVehiculo.TRAILER.puedeCircular(edge.highway)));
    }

    @Test
    void astarAndDijkstraFindTheSameOptimumWithTurnPenalties() {
        GrafoVial graph = buildGraph(List.of(
                via("residential", false, false, 30,
                        new double[]{0, 0}, new double[]{0.001, 0}),
                via("residential", false, false, 30,
                        new double[]{0.001, 0}, new double[]{0.002, 0}),
                via("primary", false, false, 50,
                        new double[]{0, 0}, new double[]{0.001, 0.001}),
                via("primary", false, false, 50,
                        new double[]{0.001, 0.001}, new double[]{0.002, 0})
        ));
        Nodo start = graph.nodoMasCercanoLonLat(0, 0);
        Nodo end = graph.nodoMasCercanoLonLat(0.002, 0);

        Pathfinder astar = new Pathfinder(graph);
        astar.setPenalizacionGiro(3);
        RutaResult astarRoute = astar.ruta(start, end);
        Pathfinder dijkstra = new Pathfinder(graph);
        dijkstra.setModo(Pathfinder.Modo.DIJKSTRA);
        dijkstra.setPenalizacionGiro(3);
        RutaResult dijkstraRoute = dijkstra.ruta(start, end);

        assertNotNull(astarRoute);
        assertNotNull(dijkstraRoute);
        assertEquals(dijkstraRoute.tiempoSegundos(), astarRoute.tiempoSegundos(), 0.001);
    }

    @Test
    void rejectsNodesFromAnotherGraphAndNullFiltersWithoutThrowing() {
        GrafoVial firstGraph = buildGraph(List.of(
                via("residential", false, false, 30,
                        new double[]{0, 0}, new double[]{0.001, 0})
        ));
        GrafoVial secondGraph = buildGraph(List.of(
                via("residential", false, false, 30,
                        new double[]{1, 1}, new double[]{1.001, 1})
        ));
        Nodo first = firstGraph.getNodos().getFirst();
        Nodo other = secondGraph.getNodos().getFirst();
        Pathfinder pathfinder = new Pathfinder(firstGraph);

        assertNull(pathfinder.ruta(first, other));
        assertNull(pathfinder.ruta(first, first, null));
        assertTrue(pathfinder.rutasAlternativas(first, other, 2).isEmpty());
    }

    @Test
    void visitsOrderedWaypointsAndHonorsRoadFilters() {
        GrafoVial graph = buildGraph(List.of(
                via("residential", false, false, 30,
                        new double[]{0, 0}, new double[]{0.001, 0}),
                via("primary", false, false, 50,
                        new double[]{0, 0}, new double[]{0, 0.001}),
                via("primary", false, false, 50,
                        new double[]{0, 0.001}, new double[]{0.001, 0}),
                via("primary", false, false, 50,
                        new double[]{0.001, 0}, new double[]{0.002, 0})
        ));
        Nodo west = graph.nodoMasCercanoLonLat(0, 0);
        Nodo north = graph.nodoMasCercanoLonLat(0, 0.001);
        Nodo east = graph.nodoMasCercanoLonLat(0.002, 0);

        RutaResult ruta = new Pathfinder(graph).ruta(List.of(west, north, east),
                edge -> TipoVehiculo.TRAILER.puedeCircular(edge.highway));

        assertNotNull(ruta);
        List<Integer> nodos = ruta.getNodos().stream().map(nodo -> nodo.id).toList();
        assertTrue(nodos.indexOf(north.id) > nodos.indexOf(west.id));
        assertTrue(nodos.indexOf(north.id) < nodos.indexOf(east.id));
        assertTrue(ruta.getEdges().stream()
                .allMatch(edge -> TipoVehiculo.TRAILER.puedeCircular(edge.highway)));
    }

    public static GrafoVial buildGraph(List<Via> vias) {
        Proyector proyector = new Proyector();
        proyector.preProyectar(vias);
        GrafoVial graph = new GrafoVial();
        graph.construir(vias, proyector);
        return graph;
    }

    private static Via via(String highway, boolean oneway, boolean reverse,
                           int maxspeed, double[]... coordinates) {
        Via via = new Via();
        via.highway = highway;
        via.oneway = oneway;
        via.reverseOneway = reverse;
        via.maxspeed = maxspeed;
        via.lanes = 1;
        via.coords = List.of(coordinates);
        return via;
    }
}
