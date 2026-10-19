package trafiqueishon.io;

import org.junit.jupiter.api.Test;
import trafiqueishon.ruta.LuisYPuntoImportante;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BetoHLectorPuntosGeoJsonTest {

    private List<LuisYPuntoImportante> leer(String json) throws Exception {
        return new BetoHLectorPuntosGeoJson().leer(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void unArchivoSoloConCallesDevuelveListaVaciaSinFallar() throws Exception {
        assertTrue(leer("""
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"highway":"residential"},
                  "geometry":{"type":"LineString","coordinates":[[-70,-15],[-69.99,-15]]}}]}
                """).isEmpty());
    }

    @Test
    void extraePuntosAmenityYTourismYElCentroDePoligonos() throws Exception {
        List<LuisYPuntoImportante> puntos = leer("""
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"amenity":"hospital","name":"Hospital Central"},
                  "geometry":{"type":"Point","coordinates":[-70.0,-15.0]}},
                 {"type":"Feature","properties":{"tourism":"museum","name":"Museo"},
                  "geometry":{"type":"Point","coordinates":[-69.9,-15.1]}},
                 {"type":"Feature","properties":{"amenity":"school","name":"Colegio"},
                  "geometry":{"type":"Polygon","coordinates":[[[-70,-15],[-70,-15.002],[-69.998,-15.002],[-69.998,-15]]]}},
                 {"type":"Feature","properties":{"amenity":"bench","name":"Banca"},
                  "geometry":{"type":"Point","coordinates":[-70.0,-15.0]}},
                 {"type":"Feature","properties":{"amenity":"school"},
                  "geometry":{"type":"Point","coordinates":[-70.0,-15.0]}},
                 {"type":"Feature","properties":{"highway":"primary"},
                  "geometry":{"type":"LineString","coordinates":[[-70,-15],[-69.99,-15]]}}]}
                """);

        assertEquals(3, puntos.size());
        assertEquals(LuisYPuntoImportante.TipoPunto.SALUD, puntos.get(0).tipo());
        assertEquals("Museo", puntos.get(1).nombre());
        assertEquals(LuisYPuntoImportante.TipoPunto.TURISMO, puntos.get(1).tipo());
        assertEquals(-69.999, puntos.get(2).lon(), 1e-9);
    }
}
