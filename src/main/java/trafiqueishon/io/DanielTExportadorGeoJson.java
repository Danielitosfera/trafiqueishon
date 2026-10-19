package trafiqueishon.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGNodo;
import trafiqueishon.ruta.LuisYPaletaColores;
import trafiqueishon.ruta.LuisYTrayecto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class DanielTExportadorGeoJson {

    private final ObjectMapper mapper = new ObjectMapper();

    public void exportar(LuisYTrayecto trayecto, Path destino) throws IOException {
        exportar(List.of(new DanielTRutaExportable(trayecto.nombre(), LuisYPaletaColores.COLOR_RUTA_IDA, trayecto)), destino);
    }

    public void exportar(List<DanielTRutaExportable> rutas, Path destino) throws IOException {
        if (rutas.isEmpty()) throw new IllegalArgumentException("Selecciona al menos una ruta.");
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "FeatureCollection");
        ArrayNode features = mapper.createArrayNode();
        for (DanielTRutaExportable ruta : rutas) {
            LuisYTrayecto trayecto = ruta.trayecto();
            ObjectNode props = mapper.createObjectNode();
            props.put("nombre", ruta.nombre());
            props.put("empresa", trayecto.empresa());
            props.put("sentido", trayecto.sentido());
            props.put("color", ruta.color());
            props.put("distancia_km", trayecto.distanciaMetros() / 1000.0);
            props.put("tiempo_min", trayecto.tiempoSegundos() / 60.0);

            ArrayNode coords = mapper.createArrayNode();
            for (PieroGEdge e : trayecto.edges()) {
                PieroGNodo n = e.origen();
                coords.add(mapper.createArrayNode().add(n.lon).add(n.lat));
            }
            if (!trayecto.edges().isEmpty()) {
                PieroGNodo ultimo = trayecto.edges().getLast().destino();
                coords.add(mapper.createArrayNode().add(ultimo.lon).add(ultimo.lat));
            }

            ObjectNode geometry = mapper.createObjectNode();
            geometry.put("type", "LineString");
            geometry.set("coordinates", coords);

            ObjectNode feature = mapper.createObjectNode();
            feature.put("type", "Feature");
            feature.set("properties", props);
            feature.set("geometry", geometry);
            features.add(feature);
        }
        root.set("features", features);

        Files.writeString(destino, mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(root));
    }
}