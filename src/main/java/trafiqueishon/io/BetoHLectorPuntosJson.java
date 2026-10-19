package trafiqueishon.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import trafiqueishon.ruta.LuisYPuntoImportante;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class BetoHLectorPuntosJson {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<LuisYPuntoImportante> leer(Path archivo) throws IOException {
        try (InputStream in = Files.newInputStream(archivo)) {
            return parsear(mapper.readTree(in));
        }
    }

    /** Lee puntos desde una entrada abierta (Jackson la cierra al terminar). */
    public List<LuisYPuntoImportante> leer(InputStream entrada) throws IOException {
        return parsear(mapper.readTree(entrada));
    }

    public List<LuisYPuntoImportante> leerRecurso(String recurso) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(recurso)) {
            if (in == null) throw new IOException("No se encontro el recurso: " + recurso);
            return parsear(mapper.readTree(in));
        }
    }

    public void guardar(List<LuisYPuntoImportante> puntos, Path archivo) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode array = root.putArray("puntos");
        for (LuisYPuntoImportante punto : puntos) {
            ObjectNode nodo = array.addObject();
            nodo.put("id", punto.id());
            nodo.put("nombre", punto.nombre());
            nodo.put("x", punto.lon());
            nodo.put("y", punto.lat());
            nodo.put("tipo", punto.tipo().name());
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(archivo.toFile(), root);
    }

    private List<LuisYPuntoImportante> parsear(JsonNode root) throws IOException {
        JsonNode arr = root.path("puntos");
        if (!arr.isArray()) throw new IOException("El archivo no contiene 'puntos'.");
        List<LuisYPuntoImportante> lista = new ArrayList<>();
        for (JsonNode p : arr) {
            String id = p.path("id").asText(null);
            String nombre = p.path("nombre").asText(id);
            double lon = p.path("x").asDouble(Double.NaN);
            double lat = p.path("y").asDouble(Double.NaN);
            String tipoStr = p.path("tipo").asText("PARADA");
            LuisYPuntoImportante.TipoPunto tipo = LuisYPuntoImportante.TipoPunto.desdeTexto(tipoStr);
            try {
                lista.add(new LuisYPuntoImportante(id, nombre, lon, lat, tipo));
            } catch (IllegalArgumentException e) {
                throw new IOException("Punto invalido: " + id + " - " + e.getMessage());
            }
        }
        return List.copyOf(lista);
    }
}