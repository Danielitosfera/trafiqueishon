package trafiqueishon.io;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import trafiqueishon.simulacion.EberthMParametrosCiudad;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Guarda los {@link EberthMParametrosCiudad} de cada ciudad en {@code config_ciudades.json}. */
public final class BetoHRepositorioConfigCiudades {

    private record ParametrosGuardados(long poblacionTotal, double porcentajeTransportePublico,
                                       String horasPunta, double probabilidadLluvia,
                                       String tasaAccidentes, Double porcentajeEstudiantes,
                                       Double porcentajeTrabajadores, Double porcentajeJubilados,
                                       Integer capacidadMaximaParada, String horasPuntaFinSemana) {}

    private static final TypeReference<Map<String, ParametrosGuardados>> TIPO =
            new TypeReference<>() {};
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path archivo;

    public BetoHRepositorioConfigCiudades() {
        this(Path.of(System.getProperty("user.home"), ".trafiqueishon", "config_ciudades.json"));
    }

    public BetoHRepositorioConfigCiudades(Path archivo) {
        this.archivo = archivo.toAbsolutePath();
    }

    /**
     * Lee el archivo; sin archivo devuelve un mapa vacío. Las ciudades con valores inválidos
     * se omiten (se usarán sus valores por defecto) en lugar de rechazar todo el archivo.
     */
    public Map<String, EberthMParametrosCiudad> cargar() throws IOException {
        Map<String, EberthMParametrosCiudad> resultado = new LinkedHashMap<>();
        if (!Files.exists(archivo)) return resultado;
        Map<String, ParametrosGuardados> guardados;
        try (var entrada = Files.newInputStream(archivo)) {
            guardados = mapper.readValue(entrada, TIPO);
        }
        if (guardados == null) return resultado;
        guardados.forEach((id, g) -> {
            try {
                var p = new EberthMParametrosCiudad(g.poblacionTotal(),
                        g.porcentajeTransportePublico(), g.horasPunta(), g.probabilidadLluvia(),
                        EberthMParametrosCiudad.TasaAccidentes.valueOf(g.tasaAccidentes()));
                // Archivos anteriores no traen estos campos: conservan los valores por defecto.
                if (g.porcentajeEstudiantes() != null && g.porcentajeTrabajadores() != null
                        && g.porcentajeJubilados() != null) {
                    p.setDemografia(g.porcentajeEstudiantes(), g.porcentajeTrabajadores(),
                            g.porcentajeJubilados());
                }
                if (g.capacidadMaximaParada() != null) p.setCapacidadMaximaParada(g.capacidadMaximaParada());
                if (g.horasPuntaFinSemana() != null) p.setHorasPuntaFinSemana(g.horasPuntaFinSemana());
                resultado.put(id, p);
            } catch (RuntimeException ex) {
                // entrada corrupta: esa ciudad vuelve a sus valores por defecto
            }
        });
        return resultado;
    }

    public void guardar(Map<String, EberthMParametrosCiudad> parametros) throws IOException {
        Map<String, ParametrosGuardados> salida = new LinkedHashMap<>();
        parametros.forEach((id, p) -> salida.put(id, new ParametrosGuardados(p.getPoblacionTotal(),
                p.getPorcentajeTransportePublico(), p.getHorasPunta(), p.getProbabilidadLluvia(),
                p.getTasaAccidentes().name(), p.getPorcentajeEstudiantes(),
                p.getPorcentajeTrabajadores(), p.getPorcentajeJubilados(),
                p.getCapacidadMaximaParada(), p.getHorasPuntaFinSemana())));
        Files.createDirectories(archivo.getParent());
        Path temporal = Files.createTempFile(archivo.getParent(), "config-ciudades-", ".tmp");
        try {
            try (var out = Files.newOutputStream(temporal)) {
                mapper.writeValue(out, salida);
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
}
