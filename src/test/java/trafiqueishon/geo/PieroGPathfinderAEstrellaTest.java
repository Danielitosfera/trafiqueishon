package trafiqueishon.geo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PieroGPathfinderAEstrellaTest {

    private static final double PASO = 0.001;

    @Test
    void encuentraLaRutaSimpleEnLinea() {
        PieroGGrafoVial grafo = grafo(via("a-b-c", punto(0, 0), punto(1, 0), punto(2, 0)));
        PieroGNodo a = nodo(grafo, 0, 0);
        PieroGNodo c = nodo(grafo, 2, 0);

        PieroGRutaResult ruta = new PieroGPathfinder(grafo).ruta(a, c);

        assertNotNull(ruta);
        assertEquals(2, ruta.getEdges().size());
        assertEquals(List.of(a, nodo(grafo, 1, 0), c), ruta.getNodos());
    }

    @Test
    void rodeaUnTramoCerradoYNoLoUsa() {
        PieroGGrafoVial grafo = grafoConDesvio();
        PieroGNodo a = nodo(grafo, 0, 0);
        PieroGNodo c = nodo(grafo, 2, 0);
        PieroGPathfinder pathfinder = new PieroGPathfinder(grafo);
        PieroGRutaResult directa = pathfinder.ruta(a, c);
        assertEquals(2, directa.getEdges().size());
        double tiempoDirecta = directa.tiempoSegundos();

        entre(grafo, nodo(grafo, 1, 0), c).cerrado = true;
        PieroGRutaResult desvio = pathfinder.ruta(a, c);

        assertNotNull(desvio);
        assertTrue(desvio.getEdges().stream().noneMatch(e -> e.cerrado));
        assertTrue(desvio.getEdges().size() > directa.getEdges().size());
        assertTrue(desvio.tiempoSegundos() > tiempoDirecta);
    }

    @Test
    void devuelveNullCuandoNoHayCaminoPosible() {
        PieroGGrafoVial grafo = grafo(
                via("izquierda", punto(0, 0), punto(1, 0)),
                via("derecha", punto(5, 5), punto(6, 5)));

        PieroGRutaResult ruta = new PieroGPathfinder(grafo).ruta(nodo(grafo, 0, 0), nodo(grafo, 6, 5));

        assertNull(ruta);
    }

    @Test
    void devuelveNullSiElUnicoCaminoEstaCerrado() {
        PieroGGrafoVial grafo = grafo(via("a-b", punto(0, 0), punto(1, 0)));
        PieroGNodo a = nodo(grafo, 0, 0);
        PieroGNodo b = nodo(grafo, 1, 0);
        entre(grafo, a, b).cerrado = true;

        assertNull(new PieroGPathfinder(grafo).ruta(a, b));
    }

    @Test
    void aEstrellaYDijkstraDanElMismoCostoOptimo() {
        PieroGGrafoVial grafo = grafoConDesvio();
        PieroGNodo a = nodo(grafo, 0, 0);
        PieroGNodo c = nodo(grafo, 2, 0);
        entre(grafo, nodo(grafo, 1, 0), c).cerrado = true;

        PieroGPathfinder aEstrella = new PieroGPathfinder(grafo);
        aEstrella.setModo(PieroGPathfinder.Modo.A_ESTRELLA);
        PieroGPathfinder dijkstra = new PieroGPathfinder(grafo);
        dijkstra.setModo(PieroGPathfinder.Modo.DIJKSTRA);

        assertEquals(dijkstra.ruta(a, c).tiempoSegundos(),
                aEstrella.ruta(a, c).tiempoSegundos(), 1e-6);
    }

    @Test
    void origenIgualADestinoDevuelveRutaVacia() {
        PieroGGrafoVial grafo = grafo(via("a-b", punto(0, 0), punto(1, 0)));
        PieroGNodo a = nodo(grafo, 0, 0);

        PieroGRutaResult ruta = new PieroGPathfinder(grafo).ruta(a, a);

        assertNotNull(ruta);
        assertTrue(ruta.esVacia());
    }

    // ------------------------------------------------------------------ ayudas

    /** A-B-C en línea (directa) y A-D-E-F-C por arriba (desvío más largo). */
    private PieroGGrafoVial grafoConDesvio() {
        return grafo(
                via("directa", punto(0, 0), punto(1, 0), punto(2, 0)),
                via("desvio", punto(0, 0), punto(0, 1), punto(1, 1), punto(2, 1), punto(2, 0)));
    }

    private static double[] punto(int x, int y) {
        return new double[]{-70.0 + x * PASO, -15.0 + y * PASO};
    }

    private static PieroGVia via(String nombre, double[]... coordenadas) {
        PieroGVia via = new PieroGVia();
        via.id = nombre;
        via.name = nombre;
        via.highway = "residential";
        via.coords = new ArrayList<>(Arrays.asList(coordenadas));
        return via;
    }

    private static PieroGGrafoVial grafo(PieroGVia... vias) {
        List<PieroGVia> lista = List.of(vias);
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(lista);
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(lista, proyector);
        return grafo;
    }

    private static PieroGNodo nodo(PieroGGrafoVial grafo, int x, int y) {
        double[] p = punto(x, y);
        return grafo.nodoMasCercanoLonLat(p[0], p[1]);
    }

    private static PieroGEdge entre(PieroGGrafoVial grafo, PieroGNodo origen, PieroGNodo destino) {
        return grafo.getEdges().stream()
                .filter(e -> e.origen == origen && e.destino == destino)
                .findFirst().orElseThrow();
    }
}
