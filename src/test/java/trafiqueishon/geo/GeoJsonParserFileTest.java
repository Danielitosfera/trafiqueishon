package trafiqueishon.geo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeoJsonParserFileTest {
    @TempDir
    Path directorio;

    @Test
    void loadsLineStringAndMultiLineStringFromAnotherCity() throws Exception {
        String geoJson = """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","properties":{"highway":"primary","name":"Avenida X"},
                   "geometry":{"type":"LineString","coordinates":[[-74.0,-13.0],[-74.001,-13.0]]}},
                  {"type":"Feature","properties":{"highway":"residential","name":"Jiron Y"},
                   "geometry":{"type":"MultiLineString","coordinates":[
                     [[-74.0,-13.001],[-74.001,-13.001]],
                     [[-74.001,-13.001],[-74.002,-13.001]]
                   ]}}
                ]}
                """;
        Path archivo = directorio.resolve("ciudad.geojson");
        Files.writeString(archivo, geoJson);

        var vias = new GeoJsonParser().parsearArchivo(archivo);

        assertEquals(3, vias.size());
        assertEquals("Avenida X", vias.getFirst().name);
        assertEquals("Jiron Y", vias.getLast().name);
    }
}
