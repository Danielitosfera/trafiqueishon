package trafiqueishon.simulacion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Persistencia JSON del plan de micros en el directorio de usuario. */
public final class RutaMicroStore {
    private static final TypeReference<List<RutaMicro>> TIPO_LISTA = new TypeReference<>() {};
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path archivo = Path.of(System.getProperty("user.home"),
            ".trafiqueishon", "rutas-micro.json");

    public List<RutaMicro> cargar() throws IOException {
        if (!Files.exists(archivo)) return List.of();
        return List.copyOf(mapper.readValue(archivo.toFile(), TIPO_LISTA));
    }

    public void guardar(List<RutaMicro> rutas) throws IOException {
        Files.createDirectories(archivo.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(archivo.toFile(), rutas);
    }

    public String importarLogo(String rutaOrigen) throws IOException {
        if (rutaOrigen == null || rutaOrigen.isBlank()) return "";
        Path origen = Path.of(rutaOrigen).toAbsolutePath().normalize();
        if (!Files.isRegularFile(origen)) {
            throw new IOException("El archivo de logo no existe: " + origen);
        }
        Path directorioLogos = archivo.getParent().resolve("logos");
        Files.createDirectories(directorioLogos);
        Path destino = directorioLogos.resolve(
                java.util.UUID.randomUUID() + "-" + origen.getFileName());
        Files.copy(origen, destino, StandardCopyOption.COPY_ATTRIBUTES);
        return destino.toString();
    }

    public Path archivo() { return archivo; }
}
