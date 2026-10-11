package trafiqueishon.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GeoJsonParser {

    private static final Pattern ENTERO = Pattern.compile("^(\\d+)");
    private static final Pattern VELOCIDAD =
            Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*(mph|km/h|kph)?");
    private final ObjectMapper mapper = new ObjectMapper();

    public List<Via> parsearDesdeRecurso(String rutaRecurso) {
        try (InputStream is = getClass().getResourceAsStream(rutaRecurso)) {
            if (is == null) {
                throw new RuntimeException("No se encontro el recurso: " + rutaRecurso);
            }
            JsonNode root = mapper.readTree(is);
            return parsear(root);
        } catch (Exception e) {
            throw new RuntimeException("Error al parsear GeoJSON: " + e.getMessage(), e);
        }
    }

    public List<Via> parsearArchivo(Path archivo) throws IOException {
        try (InputStream input = Files.newInputStream(archivo)) {
            JsonNode root = mapper.readTree(input);
            List<Via> vias = parsear(root);
            if (vias.isEmpty()) throw new IOException("El GeoJSON no contiene calles LineString.");
            return vias;
        }
    }

    private List<Via> parsear(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("La raiz GeoJSON debe ser un objeto.");
        }
        List<Via> vias = new ArrayList<>(8000);

        JsonNode features = root.get("features");
        if (features == null || !features.isArray()) return vias;

        for (JsonNode feature : features) {
            JsonNode geometry = feature.get("geometry");
            if (geometry == null) continue;
            JsonNode tipoNode = geometry.get("type");
            if (tipoNode == null || !tipoNode.isTextual()) continue;
            String tipo = tipoNode.asText();
            JsonNode props = feature.get("properties");
            JsonNode coordsNode = geometry.get("coordinates");
            if (coordsNode == null) continue;
            if ("LineString".equals(tipo)) {
                agregarVia(vias, coordsNode, props, feature, "id");
            } else if ("MultiLineString".equals(tipo)) {
                for (JsonNode linea : coordsNode) {
                    agregarVia(vias, linea, props, feature, "id");
                }
            }
        }
        return vias;
    }

    private void agregarVia(List<Via> vias, JsonNode coordsNode, JsonNode props,
                            JsonNode feature, String id) {
        int n = coordsNode.size();
        if (n < 2) return;
        List<double[]> coords = new ArrayList<>(n);
        for (JsonNode punto : coordsNode) {
            if (punto == null || !punto.isArray() || punto.size() < 2
                    || !punto.get(0).isNumber() || !punto.get(1).isNumber()) return;
            double lon = punto.get(0).asDouble();
            double lat = punto.get(1).asDouble();
            if (!Double.isFinite(lon) || !Double.isFinite(lat)
                    || lon < -180 || lon > 180 || lat < -90 || lat > 90) return;
            coords.add(new double[]{lon, lat});
        }
        if (coords.size() < 2) return;

        Via v = new Via();
        v.coords = coords;
        v.id = texto(props, "@id", feature, id);
        v.highway = texto(props, "highway", null, null);
        v.name = texto(props, "name", null, null);
        v.surface = texto(props, "surface", null, null);
        v.ref = texto(props, "ref", null, null);
        v.lanes = Math.max(1, entero(props, "lanes", 1));
        v.maxspeed = velocidadMaxima(props);
        String oneway = texto(props, "oneway", null, null);
        v.reverseOneway = "-1".equals(oneway);
        v.oneway = "yes".equals(oneway) || "1".equals(oneway)
                || "true".equals(oneway) || v.reverseOneway
                || (oneway == null
                && "roundabout".equals(texto(props, "junction", null, null)));
        v.bridge = "yes".equals(texto(props, "bridge", null, null));
        vias.add(v);
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
            String valor = props.get(clave).asText().trim();
            Matcher matcher = ENTERO.matcher(valor);
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    return porDefecto;
                }
            }
        }
        return porDefecto;
    }

    private int velocidadMaxima(JsonNode props) {
        if (props == null || !props.hasNonNull("maxspeed")) return 0;
        String valor = props.get("maxspeed").asText().trim().toLowerCase();
        Matcher matcher = VELOCIDAD.matcher(valor);
        if (!matcher.find()) return 0;
        try {
            double velocidad = Double.parseDouble(matcher.group(1));
            if ("mph".equals(matcher.group(2))) velocidad *= 1.609344;
            return velocidad > 0 && velocidad < 300
                    ? (int) Math.round(velocidad) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}