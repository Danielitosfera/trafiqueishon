package trafiqueishon.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trafiqueishon.geo.PieroGCiudadMapa;
import trafiqueishon.ruta.LuisYPuntoImportante;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DanielTImportadorMapaTest {
    @TempDir
    Path temporal;

    private static final String CON_PUNTO = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","id":"node/1","properties":{"amenity":"hospital","name":"Hospital Lima"},
               "geometry":{"type":"Point","coordinates":[-77.03,-12.04]}}]}
            """;

    private Path carpetaLima() throws Exception {
        return Files.createDirectories(temporal.resolve("mapas/lima"));
    }

    @Test
    void sobreescribePuntosJsonDeLaCiudadConLosDeSuCallesGeojson() throws Exception {
        Path carpeta = carpetaLima();
        Files.writeString(carpeta.resolve("calles.geojson"), CON_PUNTO);
        Files.writeString(carpeta.resolve("puntos.json"), "{\"puntos\":[]}");
        BetoHFuenteMapa fuente = new BetoHFuenteMapa(List.of(temporal.resolve("mapas")), false);

        var r = new DanielTImportadorMapa(fuente).extraerDeCalles(PieroGCiudadMapa.LIMA);

        assertEquals(carpeta.resolve("puntos.json"), r.guardado());
        assertEquals(1, r.encontrados());
        try (InputStream in = fuente.abrir(PieroGCiudadMapa.LIMA, BetoHFuenteMapa.PUNTOS)) {
            List<LuisYPuntoImportante> leidos = new BetoHLectorPuntosJson().leer(in);
            assertEquals("Hospital Lima", leidos.get(0).nombre());
        }
    }

    @Test
    void sinPuntosDeInteresNoTocaElArchivoExistente() throws Exception {
        Path carpeta = carpetaLima();
        Files.writeString(carpeta.resolve("calles.geojson"),
                "{\"type\":\"FeatureCollection\",\"features\":[]}");
        Files.writeString(carpeta.resolve("puntos.json"), "{\"puntos\":[]}ORIGINAL");
        BetoHFuenteMapa fuente = new BetoHFuenteMapa(List.of(temporal.resolve("mapas")), false);

        var r = new DanielTImportadorMapa(fuente).extraerDeCalles(PieroGCiudadMapa.LIMA);

        assertEquals(0, r.encontrados());
        assertTrue(Files.readString(carpeta.resolve("puntos.json")).endsWith("ORIGINAL"));
    }

    @Test
    void importarCiudadCopiaElMapaALaCarpetaDelUsuarioYApareceEnElSelector() throws Exception {
        Path crudo = Files.writeString(temporal.resolve("overpass.geojson"), CON_PUNTO);
        BetoHFuenteMapa fuente = new BetoHFuenteMapa(List.of(temporal.resolve("local"),
                temporal.resolve("usuario")), false);

        PieroGCiudadMapa ciudad = new DanielTImportadorMapa(fuente).importarCiudad(crudo, "San Martín");

        assertEquals("san-martin", ciudad.id());
        assertEquals("San Martin", ciudad.nombre());
        assertTrue(Files.isRegularFile(temporal.resolve("usuario/san-martin/calles.geojson")));
        assertEquals(List.of(ciudad), fuente.ciudadesImportadas());
        assertTrue(fuente.buscarCiudad("san-martin").isPresent());
        assertFalse(fuente.existe(ciudad, BetoHFuenteMapa.PUNTOS)); // puntos vacíos al inicio
    }

    @Test
    void importarCiudadRechazaArchivosQueNoSonGeoJsonYNombresIncluidos() throws Exception {
        Path basura = Files.writeString(temporal.resolve("x.json"), "{\"elements\":[]}");
        Path bueno = Files.writeString(temporal.resolve("ok.geojson"), CON_PUNTO);
        BetoHFuenteMapa fuente = new BetoHFuenteMapa(List.of(temporal.resolve("usuario")), false);
        var importador = new DanielTImportadorMapa(fuente);

        assertThrows(java.io.IOException.class, () -> importador.importarCiudad(basura, "Cusco"));
        assertThrows(IllegalArgumentException.class, () -> importador.importarCiudad(bueno, "Lima"));
        assertThrows(IllegalArgumentException.class, () -> importador.importarCiudad(bueno, "  "));
        assertTrue(fuente.ciudadesImportadas().isEmpty());
    }
}
