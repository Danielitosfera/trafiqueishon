package trafiqueishon.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.geo.PieroGProyector;
import trafiqueishon.geo.PieroGVia;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.ruta.LuisYTrayecto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DanielTExportadorTxtTest {

    @TempDir
    Path directorio;

    @Test
    void listaTodosLosPuntosDePartidaYRetornoEnOrdenConCoordenadas() throws IOException {
        LuisYTrayecto partida = trayecto("PARTIDA", List.of(
                punto("p1", "Jr. Chirihuano", -70.1330, -15.4990),
                punto("p2", "Av. Cannica", -70.1320, -15.4980),
                punto("p3", "Plaza Bolognesi", -70.1310, -15.4970)));
        LuisYTrayecto retorno = trayecto("RETORNO", List.of(
                punto("p4", "Sede de Colegio Trinomo", -70.1200, -15.4900),
                punto("p5", "Jr. Chirihuano", -70.1330, -15.4990)));
        Path destino = directorio.resolve("ruta.txt");

        new DanielTExportadorTxt().exportar(partida, retorno, destino);
        String texto = Files.readString(destino);

        assertTrue(texto.contains("TRAFIQUEISHON - RECORRIDO DE RUTA"));
        assertTrue(texto.contains("RECORRIDO DE PARTIDA"));
        assertTrue(texto.contains("RECORRIDO DE RETORNO"));
        assertTrue(texto.contains("1. JR. CHIRIHUANO"));
        assertTrue(texto.contains("(x=-70.13300, y=-15.49900)"));
        assertTrue(texto.contains("3. PLAZA BOLOGNESI"));
        assertTrue(texto.contains("Total: 3 puntos"));
        assertTrue(texto.contains("1. SEDE DE COLEGIO TRINOMO"));
        assertTrue(texto.contains("Total: 2 puntos"));
        assertTrue(texto.indexOf("RECORRIDO DE PARTIDA") < texto.indexOf("RECORRIDO DE RETORNO"));
        assertTrue(texto.indexOf("AV. CANNICA") < texto.indexOf("PLAZA BOLOGNESI"));
    }

    @Test
    void sinPuntosNombradosRecorreLosNodosDeLosTramos() {
        LuisYTrayecto sinPuntos = trayectoConTramos();

        String texto = new DanielTExportadorTxt().generar(List.of(
                new DanielTRutaExportable("Micro Prueba", "#000000", sinPuntos)));

        assertTrue(texto.contains("Total: 3 puntos"), texto);
        assertTrue(texto.contains("1. CALLE UNO"), texto);
    }

    @Test
    void exportaVariasRutasSeparadasPorLinea() {
        LuisYTrayecto ida = trayecto("PARTIDA", List.of(punto("a", "A", -70.1, -15.1)));
        LuisYTrayecto vuelta = trayecto("RETORNO", List.of(punto("b", "B", -70.2, -15.2)));

        String texto = new DanielTExportadorTxt().generar(List.of(
                new DanielTRutaExportable("Micro · Ida", "#000000", ida),
                new DanielTRutaExportable("Micro · Vuelta", "#000000", vuelta)));

        assertTrue(texto.contains("-".repeat(44)));
        assertTrue(texto.contains("RUTA: MICRO · IDA"));
        assertTrue(texto.contains("RUTA: MICRO · VUELTA"));
        assertTrue(texto.endsWith("=".repeat(44) + "\n"));
    }

    @Test
    void rechazaUnaListaVaciaDeRutas() {
        assertThrows(IllegalArgumentException.class,
                () -> new DanielTExportadorTxt().exportar(List.of(), directorio.resolve("x.txt")));
    }

    private static LuisYPuntoImportante punto(String id, String nombre, double lon, double lat) {
        return new LuisYPuntoImportante(id, nombre, lon, lat);
    }

    private static LuisYTrayecto trayecto(String sentido, List<LuisYPuntoImportante> puntos) {
        return new LuisYTrayecto("t-" + sentido, "Ruta " + sentido, "Empresa", puntos,
                List.of(), 0, 0, sentido);
    }

    private static LuisYTrayecto trayectoConTramos() {
        PieroGVia via = new PieroGVia();
        via.id = "v";
        via.name = "Calle Uno";
        via.highway = "residential";
        via.oneway = true;
        via.coords = new ArrayList<>(List.of(new double[]{-70.0, -15.0},
                new double[]{-69.999, -15.0}, new double[]{-69.998, -15.0}));
        List<PieroGVia> vias = List.of(via);
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(vias);
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(vias, proyector);
        List<PieroGEdge> edges = new ArrayList<>(grafo.getEdges());
        return new LuisYTrayecto("t", "Sin puntos", "Empresa", List.of(), edges, 0, 0, "PARTIDA");
    }
}
