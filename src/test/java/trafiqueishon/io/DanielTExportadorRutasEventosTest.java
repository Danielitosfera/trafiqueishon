package trafiqueishon.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.geo.PieroGProyector;
import trafiqueishon.geo.PieroGVia;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.ruta.LuisYTrayecto;
import trafiqueishon.simulacion.EberthMEventoTrafico;
import trafiqueishon.simulacion.EberthMMotorSimulacion;
import trafiqueishon.simulacion.EberthMServicioMicro;
import trafiqueishon.simulacion.EberthMTipoEvento;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DanielTExportadorRutasEventosTest {

    @TempDir
    Path carpeta;

    @Test
    void incluyeRutasEventosYEstadisticas() throws Exception {
        PieroGVia via = new PieroGVia();
        via.id = "v";
        via.name = "Calle";
        via.highway = "residential";
        via.coords = new ArrayList<>(List.of(new double[]{-70.0, -15.0}, new double[]{-69.999, -15.0}));
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(List.of(via));
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(List.of(via), proyector);
        LuisYTrayecto trayecto = new LuisYTrayecto("t", "Ruta", "Empresa",
                List.of(new LuisYPuntoImportante("p", "P", -70.0, -15.0)),
                new ArrayList<>(grafo.getEdges()), 0, 0, "PARTIDA");
        var ruta = new EberthMServicioMicro("r1", "Ruta 1", trayecto, trayecto, 10, 3, 100, "#2E9E44");
        var evento = new EberthMEventoTrafico("Choque", EberthMTipoEvento.ACCIDENTE, -69.9995, -15.0);
        Path destino = carpeta.resolve("salida.json");

        new DanielTExportadorRutasEventos().exportar("Juliaca", List.of(ruta), List.of(evento),
                EberthMMotorSimulacion.Instantanea.VACIA, destino);

        JsonNode json = new ObjectMapper().readTree(destino.toFile());
        assertEquals("Juliaca", json.get("ciudad").asText());
        JsonNode r = json.get("rutas").get(0);
        assertEquals("Ruta 1", r.get("nombre").asText());
        assertTrue(r.get("ida").get("coordenadas").size() >= 2);
        assertTrue(r.has("vuelta"));
        JsonNode e = json.get("eventos").get(0);
        assertEquals("ACCIDENTE", e.get("tipo").asText());
        assertEquals(-69.9995, e.get("lon").asDouble(), 1e-9);
        assertTrue(json.get("estadisticas").has("pasajeros_transportados"));
    }
}
