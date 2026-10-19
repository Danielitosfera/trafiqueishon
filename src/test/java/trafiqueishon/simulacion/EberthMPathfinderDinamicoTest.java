package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;
import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.geo.PieroGNodo;
import trafiqueishon.geo.PieroGPathfinder;
import trafiqueishon.geo.PieroGProyector;
import trafiqueishon.geo.PieroGRutaResult;
import trafiqueishon.geo.PieroGVia;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class EberthMPathfinderDinamicoTest {

    private static final double PASO = 0.001;

    @Test
    void laRutaInicialCoincideConElCostoDeAEstrella() {
        PieroGGrafoVial grafo = cuadricula(8);
        PieroGNodo inicio = nodo(grafo, 0, 0);
        PieroGNodo meta = nodo(grafo, 7, 7);

        EberthMPathfinderDinamico dinamico = new EberthMPathfinderDinamico(grafo);

        assertTrue(dinamico.iniciar(inicio, meta));
        assertEquals(new PieroGPathfinder(grafo).ruta(inicio, meta).tiempoSegundos(),
                dinamico.costoCamino(), 1e-6);
        assertEquals(inicio, dinamico.camino().getFirst());
        assertEquals(meta, dinamico.camino().getLast());
    }

    @Test
    void bloqueaUnaViaYReencuentraUnaRutaQueLaEvita() {
        PieroGGrafoVial grafo = cuadricula(8);
        PieroGNodo inicio = nodo(grafo, 0, 0);
        PieroGNodo meta = nodo(grafo, 7, 7);
        EberthMPathfinderDinamico dinamico = new EberthMPathfinderDinamico(grafo);
        dinamico.iniciar(inicio, meta);
        double costoAntes = dinamico.costoCamino();
        PieroGEdge bloqueada = dinamico.ruta().getEdges().get(3);

        bloqueada.cerrado = true;
        dinamico.notificarCambio(bloqueada);

        assertTrue(dinamico.hayCamino());
        PieroGRutaResult nueva = dinamico.ruta();
        assertFalse(nueva.getEdges().contains(bloqueada));
        assertTrue(nueva.getEdges().stream().noneMatch(e -> e.cerrado));
        assertEquals(new PieroGPathfinder(grafo).ruta(inicio, meta).tiempoSegundos(),
                dinamico.costoCamino(), 1e-6);
        assertTrue(dinamico.costoCamino() >= costoAntes);
    }

    @Test
    void recalculaSinReiniciarExpandiendoMenosNodosQueElCalculoInicial() {
        PieroGGrafoVial grafo = cuadricula(20);
        PieroGNodo inicio = nodo(grafo, 0, 0);
        PieroGNodo meta = nodo(grafo, 19, 19);
        EberthMPathfinderDinamico dinamico = new EberthMPathfinderDinamico(grafo);
        dinamico.iniciar(inicio, meta);
        int expansionesIniciales = dinamico.expansionesUltimoCalculo();
        PieroGEdge bloqueada = dinamico.ruta().getEdges().getFirst();

        bloqueada.cerrado = true;
        dinamico.notificarCambio(bloqueada);

        assertTrue(dinamico.hayCamino());
        assertTrue(dinamico.expansionesUltimoCalculo() < expansionesIniciales,
                "recálculo=" + dinamico.expansionesUltimoCalculo()
                        + " inicial=" + expansionesIniciales);
    }

    @Test
    void sinCambiosNoExpandeNingunNodo() {
        PieroGGrafoVial grafo = cuadricula(6);
        EberthMPathfinderDinamico dinamico = new EberthMPathfinderDinamico(grafo);
        dinamico.iniciar(nodo(grafo, 0, 0), nodo(grafo, 5, 5));

        assertEquals(0, dinamico.sincronizarCambios());
        assertEquals(0, dinamico.expansionesUltimoCalculo());
    }

    @Test
    void sinCaminoPosibleLoReportaYSeRecuperaAlReabrir() {
        PieroGGrafoVial grafo = cuadricula(2);
        PieroGNodo inicio = nodo(grafo, 0, 0);
        PieroGNodo meta = nodo(grafo, 1, 1);
        EberthMPathfinderDinamico dinamico = new EberthMPathfinderDinamico(grafo);
        dinamico.iniciar(inicio, meta);
        double costoOriginal = dinamico.costoCamino();
        List<PieroGEdge> salidas = new ArrayList<>(inicio.salientes);

        salidas.forEach(e -> e.cerrado = true);
        dinamico.notificarCambios(salidas);

        assertFalse(dinamico.hayCamino());
        assertNull(dinamico.ruta());
        assertTrue(dinamico.camino().isEmpty());

        salidas.forEach(e -> e.cerrado = false);
        dinamico.notificarCambios(salidas);

        assertTrue(dinamico.hayCamino());
        assertEquals(costoOriginal, dinamico.costoCamino(), 1e-6);
    }

    @Test
    void siguePrimerosPasosYRecalculaDesdeLaNuevaPosicion() {
        PieroGGrafoVial grafo = cuadricula(10);
        PieroGNodo meta = nodo(grafo, 9, 9);
        EberthMPathfinderDinamico dinamico = new EberthMPathfinderDinamico(grafo);
        dinamico.iniciar(nodo(grafo, 0, 0), meta);
        List<PieroGNodo> camino = dinamico.camino();

        dinamico.mover(camino.get(4));
        PieroGEdge adelante = dinamico.ruta().getEdges().get(1);
        adelante.cerrado = true;
        dinamico.notificarCambio(adelante);

        assertEquals(camino.get(4), dinamico.camino().getFirst());
        assertFalse(dinamico.ruta().getEdges().contains(adelante));
        assertEquals(new PieroGPathfinder(grafo).ruta(camino.get(4), meta).tiempoSegundos(),
                dinamico.costoCamino(), 1e-6);
    }

    @Test
    void reaccionaCuandoGestorEventosPublicaUnEventoQueBloqueaUnaVia() {
        PieroGGrafoVial grafo = cuadricula(8);
        PieroGNodo inicio = nodo(grafo, 0, 0);
        PieroGNodo meta = nodo(grafo, 7, 7);
        EberthMPathfinderDinamico dinamico = new EberthMPathfinderDinamico(grafo);
        dinamico.iniciar(inicio, meta);
        EberthMGestorEventos gestor = new EberthMGestorEventos();
        gestor.agregarListener(dinamico);
        PieroGEdge bloqueada = dinamico.ruta().getEdges().get(2);

        // Quien aplica los eventos al grafo cierra el tramo; luego se publica el evento.
        bloqueada.cerrado = true;
        gestor.publicar(List.of(new EberthMEventoTrafico("Cierre", EberthMTipoEvento.VIA_CERRADA,
                bloqueada.origen.lon, bloqueada.origen.lat)));

        assertFalse(dinamico.ruta().getEdges().contains(bloqueada));
        assertEquals(new PieroGPathfinder(grafo).ruta(inicio, meta).tiempoSegundos(),
                dinamico.costoCamino(), 1e-6);
    }

    @Test
    void absorbeCambiosDeTraficoQueNoCierranLaVia() {
        PieroGGrafoVial grafo = cuadricula(8);
        PieroGNodo inicio = nodo(grafo, 0, 0);
        PieroGNodo meta = nodo(grafo, 7, 7);
        EberthMPathfinderDinamico dinamico = new EberthMPathfinderDinamico(grafo);
        dinamico.iniciar(inicio, meta);
        PieroGEdge lenta = dinamico.ruta().getEdges().get(1);

        lenta.factorTrafico = 8.0;
        assertEquals(1, dinamico.sincronizarCambios());

        assertEquals(new PieroGPathfinder(grafo).ruta(inicio, meta).tiempoSegundos(),
                dinamico.costoCamino(), 1e-6);
    }

    @Test
    void coincideConAEstrellaTrasCierresYReaperturasAleatorios() {
        PieroGGrafoVial grafo = cuadricula(12);
        PieroGNodo inicio = nodo(grafo, 0, 0);
        PieroGNodo meta = nodo(grafo, 11, 11);
        PieroGPathfinder referencia = new PieroGPathfinder(grafo);
        EberthMPathfinderDinamico dinamico = new EberthMPathfinderDinamico(grafo);
        dinamico.iniciar(inicio, meta);
        Random azar = new Random(42);
        List<PieroGEdge> edges = grafo.getEdges();

        for (int paso = 0; paso < 150; paso++) {
            PieroGEdge e = edges.get(azar.nextInt(edges.size()));
            e.cerrado = !e.cerrado;
            dinamico.notificarCambio(e);

            PieroGRutaResult esperada = referencia.ruta(inicio, meta);
            if (esperada == null) {
                assertFalse(dinamico.hayCamino(), "paso " + paso);
            } else {
                assertTrue(dinamico.hayCamino(), "paso " + paso);
                assertEquals(esperada.tiempoSegundos(), dinamico.costoCamino(), 1e-6,
                        "paso " + paso);
                assertTrue(dinamico.ruta().getEdges().stream().noneMatch(x -> x.cerrado));
            }
        }
    }

    @Test
    void rechazaNodosAjenosAlGrafoYUsoSinIniciar() {
        PieroGGrafoVial grafo = cuadricula(3);
        PieroGGrafoVial otro = cuadricula(3);
        EberthMPathfinderDinamico dinamico = new EberthMPathfinderDinamico(grafo);

        assertThrows(IllegalStateException.class, () -> dinamico.sincronizarCambios());
        assertThrows(IllegalArgumentException.class,
                () -> dinamico.iniciar(nodo(otro, 0, 0), nodo(grafo, 2, 2)));
    }

    // ------------------------------------------------------------------ ayudas

    /** Cuadrícula n×n de calles bidireccionales. */
    private static PieroGGrafoVial cuadricula(int n) {
        List<PieroGVia> vias = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<double[]> horizontal = new ArrayList<>();
            List<double[]> vertical = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                horizontal.add(punto(j, i));
                vertical.add(punto(i, j));
            }
            vias.add(via("h" + i, horizontal));
            vias.add(via("v" + i, vertical));
        }
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(vias);
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(vias, proyector);
        return grafo;
    }

    private static double[] punto(int x, int y) {
        return new double[]{-70.0 + x * PASO, -15.0 + y * PASO};
    }

    private static PieroGVia via(String nombre, List<double[]> coordenadas) {
        PieroGVia via = new PieroGVia();
        via.id = nombre;
        via.name = nombre;
        via.highway = "residential";
        via.coords = coordenadas;
        return via;
    }

    private static PieroGNodo nodo(PieroGGrafoVial grafo, int x, int y) {
        double[] p = punto(x, y);
        return grafo.nodoMasCercanoLonLat(p[0], p[1]);
    }
}
