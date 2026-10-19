package trafiqueishon.geo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PieroGPathfinderTest {

    @Test
    void evitaTramosCerradosCuandoBuscaUnaRutaAlternativa() {
        PieroGVia directa = via("directa", "cerrada",
                new double[]{-70.0, -15.0}, new double[]{-69.999, -15.0});
        PieroGVia alternativa = via("alternativa", "abierta",
                new double[]{-70.0, -15.0}, new double[]{-69.9995, -15.0005},
                new double[]{-69.999, -15.0});
        List<PieroGVia> vias = List.of(directa, alternativa);
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(vias);
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(vias, proyector);
        grafo.getEdges().stream().filter(edge -> "cerrada".equals(edge.name))
                .forEach(edge -> edge.cerrado = true);
        PieroGNodo inicio = grafo.nodoMasCercanoLonLat(-70.0, -15.0);
        PieroGNodo fin = grafo.nodoMasCercanoLonLat(-69.999, -15.0);

        PieroGRutaResult resultado = new PieroGPathfinder(grafo).ruta(inicio, fin);

        assertNotNull(resultado);
        assertFalse(resultado.getEdges().isEmpty());
        assertTrue(resultado.getEdges().stream().allMatch(edge -> "abierta".equals(edge.name)));
    }

    private PieroGVia via(String id, String nombre, double[]... coordenadas) {
        PieroGVia via = new PieroGVia();
        via.id = id;
        via.name = nombre;
        via.highway = "residential";
        via.coords = java.util.Arrays.stream(coordenadas).toList();
        return via;
    }
}