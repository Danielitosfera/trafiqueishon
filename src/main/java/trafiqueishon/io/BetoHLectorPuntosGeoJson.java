package trafiqueishon.io;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.ruta.LuisYPuntoImportante.TipoPunto;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Extrae puntos de interés útiles (etiquetas {@code amenity} o {@code tourism} con
 * nombre) de un GeoJSON. Un archivo que solo trae calles devuelve una lista vacía: no es un error.
 * Los polígonos se reducen a su centro. Se lee en flujo para soportar mapas masivos.
 */
public final class BetoHLectorPuntosGeoJson {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<LuisYPuntoImportante> leer(Path archivo) throws IOException {
        try (InputStream entrada = Files.newInputStream(archivo)) {
            return leer(entrada);
        }
    }

    public List<LuisYPuntoImportante> leer(InputStream entrada) throws IOException {
        List<LuisYPuntoImportante> puntos = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (JsonParser json = mapper.getFactory().createParser(entrada)) {
            if (json.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("La raíz GeoJSON debe ser un objeto.");
            }
            while (json.nextToken() != JsonToken.END_OBJECT) {
                String campo = json.currentName();
                JsonToken valor = json.nextToken();
                if ("features".equals(campo) && valor == JsonToken.START_ARRAY) {
                    while (json.nextToken() != JsonToken.END_ARRAY) {
                        if (json.currentToken() != JsonToken.START_OBJECT) {
                            json.skipChildren();
                            continue;
                        }
                        LuisYPuntoImportante punto = aPunto(mapper.readTree(json), puntos.size() + 1);
                        if (punto != null && ids.add(punto.id())) puntos.add(punto);
                    }
                } else {
                    json.skipChildren();
                }
            }
        }
        return List.copyOf(puntos);
    }

    private LuisYPuntoImportante aPunto(JsonNode feature, int numero) {
        JsonNode props = feature.path("properties");
        String amenity = texto(props, "amenity");
        String tourism = texto(props, "tourism");
        if (!esUtil(amenity, tourism)) return null;
        String nombre = texto(props, "name");
        if (nombre == null) return null; // un punto sin nombre no sirve como paradero
        double[] centro = centro(feature.path("geometry"));
        if (centro == null) return null;
        String id = texto(props, "@id");
        if (id == null && feature.path("id").isValueNode()) id = feature.path("id").asText();
        if (id == null) id = "geojson_" + numero;
        try {
            return new LuisYPuntoImportante(id, nombre, centro[0], centro[1], tipo(amenity, tourism));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static final Set<String> AMENITY_UTILES = Set.of(
            "bus_station", "ferry_terminal", "taxi", "marketplace",
            "school", "kindergarten", "college", "university", "library",
            "hospital", "clinic", "doctors", "pharmacy",
            "townhall", "police", "courthouse", "fire_station", "post_office", "bank");
    private static final Set<String> TOURISM_UTILES = Set.of(
            "attraction", "museum", "viewpoint", "gallery", "zoo", "theme_park", "hotel");

    /** Solo terminales, mercados, educación, salud, instituciones y turismo; no bancas ni parqueos. */
    private static boolean esUtil(String amenity, String tourism) {
        if (tourism != null && TOURISM_UTILES.contains(tourism.toLowerCase(Locale.ROOT))) return true;
        return amenity != null && AMENITY_UTILES.contains(amenity.toLowerCase(Locale.ROOT));
    }

    private static TipoPunto tipo(String amenity, String tourism) {
        if (amenity == null) return TipoPunto.TURISMO;
        return switch (amenity.toLowerCase(Locale.ROOT)) {
            case "school", "university", "college", "kindergarten", "library" -> TipoPunto.EDUCACION;
            case "hospital", "clinic", "doctors", "pharmacy", "dentist" -> TipoPunto.SALUD;
            case "marketplace" -> TipoPunto.MERCADO;
            case "bus_station", "taxi", "ferry_terminal" -> TipoPunto.TRANSPORTE;
            case "townhall", "police", "courthouse", "fire_station", "post_office" -> TipoPunto.INSTITUCION;
            case "restaurant", "cafe", "fast_food", "bank", "bar", "fuel" -> TipoPunto.COMERCIO;
            default -> TipoPunto.POI;
        };
    }

    private static String texto(JsonNode props, String campo) {
        JsonNode n = props.get(campo);
        if (n == null || !n.isValueNode() || n.isNull()) return null;
        String t = n.asText().trim();
        return t.isEmpty() ? null : t;
    }

    /** Punto → sus coordenadas; Polygon/MultiPolygon → promedio de los vértices del primer anillo. */
    private static double[] centro(JsonNode geometria) {
        String tipo = geometria.path("type").asText("");
        JsonNode c = geometria.path("coordinates");
        return switch (tipo) {
            case "Point" -> valido(c) ? new double[]{c.get(0).asDouble(), c.get(1).asDouble()} : null;
            case "Polygon" -> promedio(c.path(0));
            case "MultiPolygon" -> promedio(c.path(0).path(0));
            default -> null;
        };
    }

    private static double[] promedio(JsonNode anillo) {
        if (!anillo.isArray() || anillo.isEmpty()) return null;
        double lon = 0, lat = 0;
        int n = 0;
        for (JsonNode p : anillo) {
            if (!valido(p)) return null;
            lon += p.get(0).asDouble();
            lat += p.get(1).asDouble();
            n++;
        }
        return new double[]{lon / n, lat / n};
    }

    private static boolean valido(JsonNode p) {
        return p.isArray() && p.size() >= 2 && p.get(0).isNumber() && p.get(1).isNumber();
    }
}
