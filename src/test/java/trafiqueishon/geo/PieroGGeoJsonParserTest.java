package trafiqueishon.geo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PieroGGeoJsonParserTest {
    @TempDir
    Path temporal;

    @Test
    void leeFeaturesEnFlujoSinCargarTodoElDocumentoComoArbol() throws Exception {
        Path archivo = temporal.resolve("calles.geojson");
        Files.writeString(archivo, """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","properties":{"highway":"residential","name":"Calle 1"},
                   "geometry":{"type":"LineString","coordinates":[[-70.0,-15.0],[-69.9,-15.1]]}},
                  {"type":"Feature","properties":{"highway":"primary","name":"Avenida"},
                   "geometry":{"type":"MultiLineString","coordinates":[
                     [[-70.2,-15.2],[-70.1,-15.1]], [[-70.1,-15.1],[-70.0,-15.0]]
                   ]}}
                ]}
                """);

        var vias = new PieroGGeoJsonParser().parsearArchivo(archivo);

        assertEquals(3, vias.size());
        assertEquals("Calle 1", vias.getFirst().name);
        assertEquals(2, vias.get(1).coords.size());
    }
}
