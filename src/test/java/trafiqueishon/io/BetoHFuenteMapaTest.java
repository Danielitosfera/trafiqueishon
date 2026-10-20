package trafiqueishon.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trafiqueishon.geo.PieroGCiudadMapa;
import trafiqueishon.geo.PieroGCoordenadasCompactas;
import trafiqueishon.geo.PieroGGeoJsonParser;
import trafiqueishon.ruta.LuisYPuntoImportante;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BetoHFuenteMapaTest {
    @TempDir
    Path temporal;

    @Test
    void juliacaSeCargaDesdeLosRecursosConCallesYPuntosSeparados() throws Exception {
        BetoHFuenteMapa fuente = new BetoHFuenteMapa(List.of(temporal), true);
        assertTrue(fuente.existe(PieroGCiudadMapa.JULIACA, BetoHFuenteMapa.CALLES));
        try (InputStream in = fuente.abrir(PieroGCiudadMapa.JULIACA, BetoHFuenteMapa.PUNTOS)) {
            List<LuisYPuntoImportante> puntos = new BetoHLectorPuntosJson().leer(in);
            assertTrue(puntos.size() > 50);
            // Las categorías del JSON ya no se pierden como PARADA.
            assertTrue(puntos.stream().anyMatch(p -> p.tipo() == LuisYPuntoImportante.TipoPunto.EDUCACION));
        }
    }

    @Test
    void laCarpetaExternaTienePrioridadYAceptaGzip() throws Exception {
        Path carpeta = Files.createDirectories(temporal.resolve("lima"));
        String geojson = """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","properties":{"highway":"primary"},
                   "geometry":{"type":"LineString","coordinates":[[-77.03,-12.04],[-77.02,-12.05]]}}]}
                """;
        try (OutputStream out = new GZIPOutputStream(
                Files.newOutputStream(carpeta.resolve("calles.geojson.gz")))) {
            out.write(geojson.getBytes(StandardCharsets.UTF_8));
        }
        BetoHFuenteMapa fuente = new BetoHFuenteMapa(List.of(temporal), false);

        try (InputStream in = fuente.abrir(PieroGCiudadMapa.LIMA, BetoHFuenteMapa.CALLES)) {
            var vias = new PieroGGeoJsonParser().parsear(in);
            assertEquals(1, vias.size());
            assertInstanceOfCompactas(vias.getFirst().coords);
            assertEquals(-77.02, vias.getFirst().coords.get(1)[0], 1e-9);
        }
        NoSuchFileException error = assertThrows(NoSuchFileException.class,
                () -> fuente.abrir(PieroGCiudadMapa.PUNO, BetoHFuenteMapa.CALLES));
        assertTrue(error.getReason().contains("Puno"));
    }

    @Test
    void tiposDePuntoSeInterpretanSinDistinguirTildesNiMayusculas() {
        assertEquals(LuisYPuntoImportante.TipoPunto.EDUCACION, LuisYPuntoImportante.TipoPunto.desdeTexto("Educación"));
        assertEquals(LuisYPuntoImportante.TipoPunto.TERMINAL, LuisYPuntoImportante.TipoPunto.desdeTexto("terminal"));
        assertEquals(LuisYPuntoImportante.TipoPunto.PARADA, LuisYPuntoImportante.TipoPunto.desdeTexto("OVNI"));
        assertEquals(LuisYPuntoImportante.TipoPunto.PARADA, LuisYPuntoImportante.TipoPunto.desdeTexto(null));
        assertEquals(PieroGCiudadMapa.LIMA, PieroGCiudadMapa.porId(" LIMA "));
        assertEquals(PieroGCiudadMapa.JULIACA, PieroGCiudadMapa.porId("desconocida"));
    }

    private static void assertInstanceOfCompactas(Object coords) {
        assertTrue(coords instanceof PieroGCoordenadasCompactas, "coords debe ser compacta");
    }

    @Test
    void eliminarCiudadBorraLaCarpetaYLaQuitaDeLaLista() throws Exception {
        Path carpeta = Files.createDirectories(temporal.resolve("cusco"));
        Files.writeString(carpeta.resolve("calles.geojson"), "{}");
        Files.writeString(carpeta.resolve("puntos.json"), "{}");
        BetoHFuenteMapa fuente = new BetoHFuenteMapa(List.of(temporal), false);
        PieroGCiudadMapa cusco = PieroGCiudadMapa.personalizada("cusco", false);
        assertTrue(fuente.ciudadesEliminables().contains(cusco));

        fuente.eliminarCiudad(cusco);

        assertFalse(Files.exists(carpeta));
        assertFalse(fuente.existe(cusco, BetoHFuenteMapa.CALLES));
        assertFalse(fuente.ciudadesEliminables().contains(cusco));
    }

    @Test
    void noSePuedeEliminarUnMapaQueVieneEnElJar() {
        BetoHFuenteMapa fuente = new BetoHFuenteMapa(List.of(temporal), true);
        assertTrue(fuente.estaEmpaquetada(PieroGCiudadMapa.JULIACA));
        assertThrows(java.io.IOException.class, () -> fuente.eliminarCiudad(PieroGCiudadMapa.JULIACA));
    }
}
