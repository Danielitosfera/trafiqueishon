package trafiqueishon.io;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import trafiqueishon.io.BetoHRepositorioConfigCiudades.ParametrosGuardados;
import trafiqueishon.io.BetoHRepositorioServiciosMicro.RutaGuardada;
import trafiqueishon.ruta.LuisYPuntoImportante;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Todo lo necesario para reconstruir la aplicación: ciudad, parámetros, rutas de micros, POIs y
 * reloj. Los tramos de las rutas no se guardan; se recalculan sobre la red de la ciudad al abrir.
 *
 * @param version        formato del archivo (para poder migrarlo)
 * @param ciudadId       carpeta de la ciudad (también identifica un mapa importado por el usuario)
 * @param ciudadNombre   nombre visible de la ciudad
 * @param ciudadGrande   si el mapa es masivo (carga con LOD agresivo)
 * @param horaMinutos    hora del reloj de simulación, en minutos del día
 * @param tipoDia        nombre de {@code EberthMTipoDia}
 */
public record DanielTEstadoProyecto(int version, String ciudadId, String ciudadNombre,
                                    boolean ciudadGrande, ParametrosGuardados parametros,
                                    List<RutaGuardada> rutas, List<LuisYPuntoImportante> puntos,
                                    int horaMinutos, String tipoDia, boolean eventosOrganicos) {

    public static final int VERSION_ACTUAL = 1;
    public static final String EXTENSION = ".traf";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public DanielTEstadoProyecto {
        rutas = rutas == null ? List.of() : List.copyOf(rutas);
        puntos = puntos == null ? List.of() : List.copyOf(puntos);
    }

    /** Escribe el proyecto de forma atómica: un fallo a medias no destruye el archivo anterior. */
    public void guardar(Path archivo) throws IOException {
        Path destino = archivo.toAbsolutePath();
        Path carpeta = destino.getParent();
        Files.createDirectories(carpeta);
        Path temporal = Files.createTempFile(carpeta, "proyecto-", ".tmp");
        try {
            try (var salida = Files.newOutputStream(temporal)) {
                MAPPER.writeValue(salida, this);
            }
            try {
                Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporal);
        }
    }

    /** @throws IOException si el archivo no es un proyecto válido */
    public static DanielTEstadoProyecto cargar(Path archivo) throws IOException {
        DanielTEstadoProyecto estado;
        try (var entrada = Files.newInputStream(archivo)) {
            estado = MAPPER.readValue(entrada, DanielTEstadoProyecto.class);
        }
        if (estado == null || estado.ciudadId() == null || estado.ciudadId().isBlank()
                || estado.version() < 1 || estado.version() > VERSION_ACTUAL) {
            throw new IOException("El archivo no es un proyecto de Trafiqueishon válido.");
        }
        return estado;
    }
}
