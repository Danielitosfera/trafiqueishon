package trafiqueishon.io;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.simulacion.EberthMServicioMicro;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class BetoHRepositorioServiciosMicro {
    private static final TypeReference<List<RutaGuardada>> TIPO_RUTAS = new TypeReference<>() {};
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final Path archivo;

    public BetoHRepositorioServiciosMicro() {
        this(Path.of(System.getProperty("user.home"), ".trafiqueishon", "rutas-micros.json"));
    }

    public BetoHRepositorioServiciosMicro(Path archivo) {
        this.archivo = archivo.toAbsolutePath();
    }

    /**
     * Repositorio de la ciudad indicada. Juliaca conserva el archivo histórico
     * {@code rutas-micros.json}; las demás usan {@code rutas-micros-<id>.json},
     * para que las rutas de una ciudad nunca se reconstruyan sobre otra red.
     */
    public static BetoHRepositorioServiciosMicro paraCiudad(trafiqueishon.geo.PieroGCiudadMapa ciudad) {
        String nombre = ciudad.equals(trafiqueishon.geo.PieroGCiudadMapa.JULIACA)
                ? "rutas-micros.json" : "rutas-micros-" + ciudad.id() + ".json";
        return new BetoHRepositorioServiciosMicro(
                Path.of(System.getProperty("user.home"), ".trafiqueishon", nombre));
    }

    /** Borra las rutas guardadas de una ciudad eliminada; no toca Juliaca (su mapa viene en el jar). */
    public static void eliminarDeCiudad(trafiqueishon.geo.PieroGCiudadMapa ciudad) throws IOException {
        if (ciudad.equals(trafiqueishon.geo.PieroGCiudadMapa.JULIACA)) return;
        Files.deleteIfExists(Path.of(System.getProperty("user.home"), ".trafiqueishon",
                "rutas-micros-" + ciudad.id() + ".json"));
    }

    public List<RutaGuardada> cargar() throws IOException {
        if (!Files.exists(archivo)) return List.of();
        try (var entrada = Files.newInputStream(archivo)) {
            List<RutaGuardada> rutas = mapper.readValue(entrada, TIPO_RUTAS);
            if (rutas == null || rutas.stream().anyMatch(r -> r == null
                    || r.id() == null || r.nombre() == null
                    || r.paraderos() == null || r.paraderos().size() < 2)) {
                throw new IOException("El archivo de rutas guardadas contiene rutas inválidas.");
            }
            return List.copyOf(rutas);
        }
    }

    public void guardar(List<EberthMServicioMicro> servicios) throws IOException {
        List<RutaGuardada> rutas = servicios.stream().map(BetoHRepositorioServiciosMicro::aGuardada).toList();
        Files.createDirectories(archivo.getParent());
        Path temporal = Files.createTempFile(archivo.getParent(), "rutas-micros-", ".tmp");
        try {
            try (var salida = Files.newOutputStream(temporal)) {
                mapper.writeValue(salida, rutas);
            }
            try {
                Files.move(temporal, archivo, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporal, archivo, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporal);
        }
    }

    public Path ubicacionArchivo() {
        return archivo;
    }

    /** Forma serializable de un servicio: solo paraderos y datos; los tramos se recalculan al cargar. */
    public static RutaGuardada aGuardada(EberthMServicioMicro servicio) {
        return new RutaGuardada(
                servicio.id(), servicio.nombre(),
                servicio.trayecto().puntos(),
                servicio.frecuenciaMinutos(), servicio.unidadesDisponibles(),
                servicio.pasajerosEsperando(), servicio.color(),
                servicio.retorno().name(),
                servicio.retorno() == trafiqueishon.simulacion.EberthMTipoRetorno.MANUAL
                        ? servicio.trayectoVuelta().puntos() : null,
                servicio.frecuenciaPuntaMinutos());
    }

    public record RutaGuardada(String id, String nombre, List<LuisYPuntoImportante> paraderos,
                               int frecuenciaMinutos, int unidadesDisponibles,
                               int pasajerosEsperando, String color,
                               String retorno, List<LuisYPuntoImportante> paraderosVuelta,
                               int frecuenciaPuntaMinutos) {
        public RutaGuardada {
            paraderos = paraderos == null ? List.of() : List.copyOf(paraderos);
            paraderosVuelta = paraderosVuelta == null ? List.of() : List.copyOf(paraderosVuelta);
        }

        /** Ruta con retorno inverso, el formato de los archivos anteriores. */
        public RutaGuardada(String id, String nombre, List<LuisYPuntoImportante> paraderos,
                            int frecuenciaMinutos, int unidadesDisponibles,
                            int pasajerosEsperando, String color) {
            this(id, nombre, paraderos, frecuenciaMinutos, unidadesDisponibles,
                    pasajerosEsperando, color, null, null, 0);
        }

        /** Formato anterior a la frecuencia de hora punta propia ({@code 0} = derivada). */
        public RutaGuardada(String id, String nombre, List<LuisYPuntoImportante> paraderos,
                            int frecuenciaMinutos, int unidadesDisponibles,
                            int pasajerosEsperando, String color,
                            String retorno, List<LuisYPuntoImportante> paraderosVuelta) {
            this(id, nombre, paraderos, frecuenciaMinutos, unidadesDisponibles,
                    pasajerosEsperando, color, retorno, paraderosVuelta, 0);
        }
    }
}
