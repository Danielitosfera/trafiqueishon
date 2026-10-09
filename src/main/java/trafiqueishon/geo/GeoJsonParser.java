package trafiqueishon.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class GeoJsonParser {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<Via> parsearDesdeRecurso(String rutaRecurso) {
        try (InputStream is = getClass().getResourceAsStream(rutaRecurso)) {
            if (is == null) {
                throw new RuntimeException("No se encontro el recurso: " + rutaRecurso);
            }
            JsonNode root = mapper.readTree(is);
            List<Via> vias = parsear(root);
            // Liberar el arbol de Jackson
            root = null;
            return vias;
        } catch (Exception e) {
            throw new RuntimeException("Error al parsear GeoJSON: " + e.getMessage(), e);
        }
    }

    private List<Via> parsear(JsonNode root) {
        List<Via> vias = new ArrayList<>(8000);

        JsonNode features = root.get("features");
        if (features == null || !features.isArray()) return vias;

        for (JsonNode feature : features) {
            JsonNode geometry = feature.get("geometry");
            if (geometry == null) continue;
            String tipo = geometry.get("type").asText();
            if (!"LineString".equals(tipo)) continue;

            JsonNode props = feature.get("properties");
            JsonNode coordsNode = geometry.get("coordinates");
            if (coordsNode == null) continue;

            int n = coordsNode.size();
            List<double[]> coords = new ArrayList<>(n);
            for (JsonNode punto : coordsNode) {
                coords.add(new double[]{punto.get(0).asDouble(), punto.get(1).asDouble()});
            }

            Via v = new Via();
            v.coords   = coords;
            v.id       = texto(props, "@id", feature, "id");
            v.highway  = texto(props, "highway", null, null);
            v.name     = texto(props, "name", null, null);
            v.surface  = texto(props, "surface", null, null);
            v.ref      = texto(props, "ref", null, null);
            v.lanes    = entero(props, "lanes", 1);
            v.maxspeed = entero(props, "maxspeed", 0);
            v.oneway   = "yes".equals(texto(props, "oneway", null, null));
            v.bridge   = "yes".equals(texto(props, "bridge", null, null));

            vias.add(v);
        }
        return vias;
    }

    private String texto(JsonNode props, String clave, JsonNode feature, String claveAlt) {
        if (props != null && props.has(clave) && !props.get(clave).isNull()) {
            return props.get(clave).asText();
        }
        if (feature != null && claveAlt != null && feature.has(claveAlt)) {
            return feature.get(claveAlt).asText();
        }
        return null;
    }

    private int entero(JsonNode props, String clave, int porDefecto) {
        if (props != null && props.has(clave) && !props.get(clave).isNull()) {
            try { return props.get(clave).asInt(porDefecto); }
            catch (Exception e) { return porDefecto; }
        }
        return porDefecto;
    }
}