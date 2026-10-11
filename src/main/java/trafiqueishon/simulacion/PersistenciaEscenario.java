package trafiqueishon.simulacion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Almacenamiento local de POI creados por el usuario y controles de simulacion. */
public final class PersistenciaEscenario {
    private static final TypeReference<List<MarcadorReferencia>> TIPO_REFERENCIAS =
            new TypeReference<>() {};
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path directorio;
    private final Path archivoReferencias;
    private final Path archivoPreferencias;
    private final Path archivoGeoJson;

    public PersistenciaEscenario() {
        this(Path.of(System.getProperty("user.home"), ".trafiqueishon"));
    }

    public PersistenciaEscenario(Path directorio) {
        this.directorio = directorio.toAbsolutePath().normalize();
        archivoReferencias = this.directorio.resolve("referencias.json");
        archivoPreferencias = this.directorio.resolve("simulacion.json");
        archivoGeoJson = this.directorio.resolve("red-vial.txt");
    }

    public List<MarcadorReferencia> cargarReferencias() throws IOException {
        if (!Files.exists(archivoReferencias)) return List.of();
        return List.copyOf(mapper.readValue(archivoReferencias.toFile(), TIPO_REFERENCIAS));
    }

    public void guardarReferencias(List<MarcadorReferencia> referencias) throws IOException {
        Files.createDirectories(directorio);
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(archivoReferencias.toFile(), referencias);
    }

    public Preferencias cargarPreferencias() throws IOException {
        if (!Files.exists(archivoPreferencias)) return Preferencias.porDefecto();
        return mapper.readValue(archivoPreferencias.toFile(), Preferencias.class);
    }

    public void guardarPreferencias(Preferencias preferencias) throws IOException {
        Files.createDirectories(directorio);
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(archivoPreferencias.toFile(), preferencias);
    }

    public Path cargarGeoJsonSeleccionado() throws IOException {
        if (!Files.exists(archivoGeoJson)) return null;
        String valor = Files.readString(archivoGeoJson).trim();
        if (valor.isEmpty()) return null;
        Path seleccionado = Path.of(valor);
        return Files.isRegularFile(seleccionado) ? seleccionado : null;
    }

    public void guardarGeoJsonSeleccionado(Path geoJson) throws IOException {
        Files.createDirectories(directorio);
        Files.writeString(archivoGeoJson, geoJson.toAbsolutePath().normalize().toString());
    }

    public record Preferencias(int flotaInicial, int limiteVehiculos, double escalaTiempo,
                               Map<String, Double> tasas, boolean semaforosAdaptativos) {
        public Preferencias {
            tasas = tasas == null ? Map.of() : Map.copyOf(tasas);
        }

        public static Preferencias porDefecto() {
            return new Preferencias(250, 10_000, 5, Map.of(), false);
        }
    }
}
