package trafiqueishon.ruta;

import org.junit.jupiter.api.Test;
import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.geo.PieroGProyector;
import trafiqueishon.geo.PieroGVia;

import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class LuisYCalculadorTrayectoTest {

    @Test
    void ordenaParaderosEntreLosDosMasAlejadosUsandoDijkstra() {
        PieroGVia via = new PieroGVia();
        via.id = "linea";
        via.highway = "residential";
        via.name = "Avenida";
        via.coords = List.of(new double[]{-70.0, -15.0},
                new double[]{-69.999, -15.0},
                new double[]{-69.998, -15.0},
                new double[]{-69.997, -15.0});
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(List.of(via));
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(List.of(via), proyector);
        List<LuisYPuntoImportante> puntos = List.of(
                new LuisYPuntoImportante("medio2", "Medio 2", -69.998, -15),
                new LuisYPuntoImportante("fin", "Terminal", -69.997, -15,
                        LuisYPuntoImportante.TipoPunto.DESTINO),
                new LuisYPuntoImportante("medio1", "Medio 1", -69.999, -15),
                new LuisYPuntoImportante("inicio", "Inicio", -70.0, -15,
                        LuisYPuntoImportante.TipoPunto.ORIGEN));

        LuisYTrayecto trayecto = new LuisYCalculadorTrayecto(grafo).calcularOrdenOptimo(
                "linea-1", "Línea", "Empresa", "SERVICIO", puntos);

        assertEquals("inicio", trayecto.puntos().getFirst().id());
        assertEquals("fin", trayecto.puntos().getLast().id());
        assertEquals(4, trayecto.puntos().stream().map(LuisYPuntoImportante::id)
                .distinct().count());
        assertTrue(trayecto.edges().stream().allMatch(edge -> !edge.cerrado));
    }

    @Test
    void optimizaExactamenteLaRutaDeDiecisieteParaderos() {
        List<double[]> coordenadas = new ArrayList<>();
        List<LuisYPuntoImportante> puntos = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            double lon = -70.0 + i * 0.0001;
            coordenadas.add(new double[]{lon, -15});
            LuisYPuntoImportante.TipoPunto tipo = i == 0 ? LuisYPuntoImportante.TipoPunto.ORIGEN
                    : i == 16 ? LuisYPuntoImportante.TipoPunto.DESTINO
                    : LuisYPuntoImportante.TipoPunto.PARADA;
            puntos.add(new LuisYPuntoImportante("p" + i, "Paradero " + i, lon, -15, tipo));
        }
        PieroGVia via = new PieroGVia();
        via.id = "linea-larga";
        via.highway = "residential";
        via.coords = coordenadas;
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(List.of(via));
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(List.of(via), proyector);
        java.util.Collections.reverse(puntos);

        LuisYTrayecto trayecto = new LuisYCalculadorTrayecto(grafo).calcularOrdenOptimo(
                "larga", "Línea larga", "Empresa", "SERVICIO", puntos);

        assertEquals("p0", trayecto.puntos().getFirst().id());
        assertEquals("p16", trayecto.puntos().getLast().id());
        for (int i = 0; i < 17; i++) {
            assertEquals("p" + i, trayecto.puntos().get(i).id());
        }
    }

    @Test
    void eligeTerminalesPorDistanciaRealDeLaRedYNoSoloPorProximidadGeografica() {
        double[] a = {-70.0, -15.0};
        double[] b = {-69.999, -15.0};
        double[] c = {-69.998, -15.0};
        PieroGVia atajo = via("atajo", "trunk", a, c);
        PieroGVia caminoAB = via("ab", "residential", a,
                new double[]{-70.0, -15.002}, new double[]{-69.999, -15.002}, b);
        PieroGVia caminoBC = via("bc", "residential", b,
                new double[]{-69.999, -15.008}, new double[]{-69.998, -15.008}, c);
        List<PieroGVia> vias = List.of(atajo, caminoAB, caminoBC);
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(vias);
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(vias, proyector);
        List<LuisYPuntoImportante> paraderos = List.of(
                new LuisYPuntoImportante("a", "A", a[0], a[1]),
                new LuisYPuntoImportante("b", "B", b[0], b[1]),
                new LuisYPuntoImportante("c", "C", c[0], c[1]));

        LuisYTrayecto resultado = new LuisYCalculadorTrayecto(grafo).calcularOrdenOptimo(
                "red", "Ruta por red", "Empresa", "SERVICIO", paraderos);

        assertEquals("b", resultado.puntos().getFirst().id());
        assertEquals("c", resultado.puntos().getLast().id());
    }

    private PieroGVia via(String id, String highway, double[]... coordenadas) {
        PieroGVia via = new PieroGVia();
        via.id = id;
        via.name = id;
        via.highway = highway;
        via.coords = java.util.Arrays.stream(coordenadas).toList();
        return via;
    }

    @Test
    void unaParadaCercaDeUnCallejonNoMeteLaRutaEnElCallejon() {
        PieroGVia principal = new PieroGVia();
        principal.id = "principal";
        principal.highway = "residential";
        principal.name = "Principal";
        principal.coords = List.of(new double[]{-70.0, -15.0}, new double[]{-69.999, -15.0},
                new double[]{-69.998, -15.0});
        PieroGVia callejon = new PieroGVia();
        callejon.id = "callejon";
        callejon.highway = "residential";
        callejon.name = "Callejon";
        callejon.coords = List.of(new double[]{-69.999, -15.0}, new double[]{-69.999, -14.9998});
        List<PieroGVia> vias = List.of(principal, callejon);
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(vias);
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(vias, proyector);
        // El nodo más cercano a la parada es la punta del callejón, pero el cruce sirve igual.
        List<LuisYPuntoImportante> puntos = List.of(
                new LuisYPuntoImportante("a", "A", -70.0, -15.0),
                new LuisYPuntoImportante("p", "Parada", -69.999, -14.99988),
                new LuisYPuntoImportante("c", "C", -69.998, -15.0));

        LuisYTrayecto trayecto = new LuisYCalculadorTrayecto(grafo)
                .calcular("t", "T", "Empresa", "SERVICIO", puntos);

        assertTrue(trayecto.edges().stream().noneMatch(e -> "Callejon".equals(e.name)));
        assertEquals(2, trayecto.edges().size());
    }
}
