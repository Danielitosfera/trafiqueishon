package trafiqueishon.simulacion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class CargadorPuntosInteres {
    private final ObjectMapper mapper = new ObjectMapper();

    public List<PuntoInteres> cargarRecurso(String recurso) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(recurso)) {
            if (input == null) throw new IOException("No se encontro el recurso: " + recurso);
            JsonNode pois = mapper.readTree(input).path("pois");
            if (!pois.isArray()) throw new IOException("El recurso no contiene una lista 'pois'.");

            List<PuntoInteres> resultado = new ArrayList<>();
            for (JsonNode poi : pois) {
                JsonNode id = poi.get("id");
                JsonNode lon = poi.get("x");
                JsonNode lat = poi.get("y");
                if (id == null || !id.isTextual() || lon == null || !lon.isNumber()
                        || lat == null || !lat.isNumber()) {
                    throw new IOException("Punto de interes sin id o coordenadas validas.");
                }
                JsonNode nombre = poi.get("nombre");
                String nombreTexto = nombre != null && nombre.isTextual()
                        ? nombre.asText() : id.asText();
                JsonNode peso = poi.get("peso");
                if (peso != null && !peso.isNull()
                        && (!peso.canConvertToInt() || peso.asInt() < 1)) {
                    throw new IOException("Peso invalido para el punto: " + id.asText());
                }
                int pesoValor = peso == null || peso.isNull() ? 1 : peso.asInt();
                try {
                    resultado.add(new PuntoInteres(
                            id.asText(), nombreTexto, lon.asDouble(), lat.asDouble(), pesoValor));
                } catch (IllegalArgumentException e) {
                    throw new IOException("Punto de interes invalido: " + id.asText(), e);
                }
            }
            if (resultado.size() < 2) {
                throw new IOException("Se requieren al menos dos puntos de interes.");
            }
            return List.copyOf(resultado);
        }
    }
}
