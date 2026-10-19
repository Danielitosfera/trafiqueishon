package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;
import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.geo.PieroGProyector;
import trafiqueishon.geo.PieroGVia;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EberthMAplicadorEventosTest {

    @Test
    void cierraSoloLosTramosCercanosYReiniciaAlQuitarElEvento() {
        PieroGGrafoVial grafo = grafo();
        PieroGEdge cercano = grafo.getEdges().getFirst();
        double lon = (cercano.origen.lon + cercano.destino.lon) / 2;
        double lat = (cercano.origen.lat + cercano.destino.lat) / 2;

        List<PieroGEdge> cerradas = EberthMAplicadorEventos.aplicar(grafo,
                List.of(new EberthMEventoTrafico("Cierre", EberthMTipoEvento.VIA_CERRADA, lon, lat))).cerradas();

        assertFalse(cerradas.isEmpty());
        assertTrue(cercano.cerrado);
        long lejanosCerrados = grafo.getEdges().stream()
                .filter(e -> e.cerrado && Math.min(e.origen.lon, e.destino.lon) > -69.99).count();
        assertEquals(0, lejanosCerrados);

        EberthMAplicadorEventos.aplicar(grafo, List.of());
        assertTrue(grafo.getEdges().stream().noneMatch(e -> e.cerrado));
    }

    @Test
    void laLluviaNoTocaElGrafoYSoloAfectaDentroDeSuRadio() {
        PieroGGrafoVial grafo = grafo();
        PieroGEdge edge = grafo.getEdges().getFirst();
        EberthMAplicadorEventos.aplicar(grafo, List.of(new EberthMEventoTrafico("Lluvia",
                EberthMTipoEvento.LLUVIA, edge.origen.lon, edge.origen.lat)));
        assertFalse(edge.cerrado);
        assertEquals(1.0, edge.factorTrafico, 1e-9);
        var lluvia = List.of(new EberthMEventoTrafico("Lluvia",
                EberthMTipoEvento.LLUVIA, edge.origen.lon, edge.origen.lat));
        assertTrue(EberthMAplicadorEventos.estaBajoLluvia(edge.origen.lon, edge.origen.lat, lluvia));
        assertTrue(EberthMAplicadorEventos.estaBajoLluvia(
                edge.origen.lon + 0.005, edge.origen.lat, lluvia));   // ~550 m
        assertFalse(EberthMAplicadorEventos.estaBajoLluvia(
                edge.origen.lon + 0.02, edge.origen.lat, lluvia));    // ~2 km
    }

    @Test
    void elAccidenteCierraSoloElTramoDelClic() {
        PieroGGrafoVial grafo = grafo();
        PieroGEdge edge = grafo.getEdges().getFirst();
        EberthMAplicadorEventos.aplicar(grafo, List.of(new EberthMEventoTrafico("Choque",
                EberthMTipoEvento.ACCIDENTE, (edge.origen.lon + edge.destino.lon) / 2, edge.origen.lat)));
        assertTrue(edge.cerrado);
        assertEquals(2, grafo.getEdges().stream().filter(e -> e.cerrado).count());
    }

    @Test
    void elSemaforoRotoMarcaElNodoMasCercano() {
        PieroGGrafoVial grafo = grafo();
        PieroGEdge edge = grafo.getEdges().getFirst();
        EberthMAplicadorEventos.aplicar(grafo, List.of(new EberthMEventoTrafico("Semáforo",
                EberthMTipoEvento.SEMAFORO_ROTO, edge.destino.lon, edge.destino.lat)));
        assertTrue(edge.destino.semaforoRoto);
        assertTrue(grafo.getEdges().stream().noneMatch(e -> e.cerrado));
        EberthMAplicadorEventos.aplicar(grafo, List.of());
        assertFalse(edge.destino.semaforoRoto);
    }

    @Test
    void laTrancaderaBajaLaVelocidadA10PorCientoSinCerrar() {
        PieroGGrafoVial grafo = grafo();
        PieroGEdge edge = grafo.getEdges().getFirst();
        var resultado = EberthMAplicadorEventos.aplicar(grafo, List.of(evento(EberthMTipoEvento.TRANCADERA, edge)));
        assertFalse(edge.cerrado);
        assertEquals(10, edge.factorTrafico, 1e-9);
        assertEquals(1.0, grafo.getEdges().getLast().factorTrafico, 1e-9);
        assertTrue(resultado.trancaderas().contains(edge));
    }

    @Test
    void losVendedoresBajanLaVelocidadAlMitadYAumentanLaDemandaCercana() {
        PieroGGrafoVial grafo = grafo();
        PieroGEdge edge = grafo.getEdges().getFirst();
        EberthMEventoTrafico vendedores = evento(EberthMTipoEvento.VENDEDORES_AMBULANTES, edge);
        EberthMAplicadorEventos.aplicar(grafo, List.of(vendedores));
        assertFalse(edge.cerrado);
        assertEquals(2.0, edge.factorTrafico, 1e-9);
        assertEquals(3.0, EberthMAplicadorEventos.multiplicadorDemanda(edge.origen, List.of(vendedores)), 1e-9);
        PieroGEdge lejano = grafo.getEdges().getLast();
        assertEquals(1.0, EberthMAplicadorEventos.multiplicadorDemanda(lejano.origen, List.of(vendedores)), 1e-9);
    }

    @Test
    void laManifestacionBloqueaElNodoYTodosSusTramosAdyacentes() {
        PieroGGrafoVial grafo = grafo();
        PieroGEdge edge = grafo.getEdges().getFirst();
        EberthMAplicadorEventos.aplicar(grafo, List.of(new EberthMEventoTrafico("Marcha",
                EberthMTipoEvento.MANIFESTACION, edge.destino.lon, edge.destino.lat)));
        edge.destino.salientes.forEach(e -> assertTrue(e.cerrado));
        edge.destino.entrantes.forEach(e -> assertTrue(e.cerrado));
        assertTrue(edge.cerrado);
        assertFalse(grafo.getEdges().getLast().cerrado);
    }

    private static EberthMEventoTrafico evento(EberthMTipoEvento tipo, PieroGEdge edge) {
        return new EberthMEventoTrafico("e", tipo, (edge.origen.lon + edge.destino.lon) / 2,
                (edge.origen.lat + edge.destino.lat) / 2);
    }

    private PieroGGrafoVial grafo() {
        PieroGVia cerca = new PieroGVia();
        cerca.highway = "residential";
        cerca.coords = List.of(new double[]{-70.0, -15.0}, new double[]{-69.999, -15.0});
        PieroGVia lejos = new PieroGVia();
        lejos.highway = "residential";
        lejos.coords = List.of(new double[]{-69.98, -15.0}, new double[]{-69.979, -15.0});
        List<PieroGVia> vias = List.of(cerca, lejos);
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(vias);
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(vias, proyector);
        return grafo;
    }
}
