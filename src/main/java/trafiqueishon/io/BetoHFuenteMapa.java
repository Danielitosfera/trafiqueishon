package trafiqueishon.io;

import trafiqueishon.geo.PieroGCiudadMapa;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Localiza los archivos de cada ciudad. Busca primero en carpetas externas
 * (para mapas masivos como Lima, que no deben ir dentro del jar, y para lo que el usuario
 * guarda o importa) y por último en los recursos del classpath. Solo se escribe en la carpeta
 * del usuario. Acepta la variante comprimida {@code .gz}.
 *
 * <p>Orden de búsqueda por defecto:</p>
 * <ol>
 *   <li>{@code ./mapas/<ciudad>/}</li>
 *   <li>{@code ~/.trafiqueishon/mapas/<ciudad>/}</li>
 *   <li>classpath {@code /mapas/<ciudad>/}</li>
 * </ol>
 */
public final class BetoHFuenteMapa {

    public static final String CALLES = "calles.geojson";
    public static final String PUNTOS = "puntos.json";

    /** Desde este tamaño un mapa se carga con LOD agresivo. */
    private static final long MAPA_GRANDE_BYTES = 40L << 20;

    private final List<Path> basesExternas;
    private final boolean usarClasspath;

    public BetoHFuenteMapa() {
        this(List.of(Path.of("mapas"),
                Path.of(System.getProperty("user.home"), ".trafiqueishon", "mapas")), true);
    }

    public BetoHFuenteMapa(List<Path> basesExternas, boolean usarClasspath) {
        this.basesExternas = List.copyOf(basesExternas);
        this.usarClasspath = usarClasspath;
    }

    /** Lugar concreto donde se encontró un archivo. */
    public record Origen(String descripcion, Path archivo, String recurso, boolean comprimido) {}

    public Optional<Origen> localizar(PieroGCiudadMapa ciudad, String nombreArchivo) {
        for (Path base : basesExternas) {
            Path carpeta = base.resolve(ciudad.id());
            Path plano = carpeta.resolve(nombreArchivo);
            if (Files.isRegularFile(plano)) {
                return Optional.of(new Origen(plano.toAbsolutePath().toString(), plano, null, false));
            }
            Path gz = carpeta.resolve(nombreArchivo + ".gz");
            if (Files.isRegularFile(gz)) {
                return Optional.of(new Origen(gz.toAbsolutePath().toString(), gz, null, true));
            }
        }
        if (usarClasspath) {
            String recurso = "/" + ciudad.carpeta() + "/" + nombreArchivo;
            if (existeRecurso(recurso)) {
                return Optional.of(new Origen("recurso " + recurso, null, recurso, false));
            }
            if (existeRecurso(recurso + ".gz")) {
                return Optional.of(new Origen("recurso " + recurso + ".gz", null,
                        recurso + ".gz", true));
            }
        }
        return Optional.empty();
    }

    /** Comprueba un recurso del classpath (IntelliJ, jar o instalable) sin dejar el flujo abierto. */
    private static boolean existeRecurso(String recurso) {
        try (InputStream in = BetoHFuenteMapa.class.getResourceAsStream(recurso)) {
            return in != null;
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * Carpeta donde se escriben los archivos de la ciudad: siempre la del usuario
     * ({@code ~/.trafiqueishon/mapas/<ciudad>/}), que se crea si no existe. Nunca se escribe en
     * {@code ./mapas} (el directorio de trabajo puede ser de solo lectura, p. ej. un .deb) ni en
     * los recursos del jar. Se lee antes que el classpath, por lo que lo guardado aquí tiene prioridad.
     */
    public Path carpetaEscritura(PieroGCiudadMapa ciudad) throws IOException {
        Path carpeta = carpetaUsuario(ciudad);
        try {
            Files.createDirectories(carpeta);
        } catch (IOException ex) {
            throw new IOException("No se pudo crear la carpeta " + carpeta + " para guardar los archivos de "
                    + ciudad.nombre() + ".", ex);
        }
        if (!Files.isWritable(carpeta)) {
            throw new IOException("No hay permiso de escritura en " + carpeta + ".");
        }
        return carpeta;
    }

    /**
     * Carpeta del usuario para una ciudad importada: la última base externa, por defecto
     * {@code ~/.trafiqueishon/mapas/<ciudad>/}. Es la que sobrevive a recompilar o actualizar la app.
     */
    public Path carpetaUsuario(PieroGCiudadMapa ciudad) {
        return basesExternas.getLast().resolve(ciudad.id());
    }

    /** El mapa no se puede borrar porque otro proceso (o esta misma app) tiene archivos abiertos. */
    public static final class MapaEnUsoException extends IOException {
        public MapaEnUsoException(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    /** Carpetas externas de la ciudad que existen ahora mismo. */
    public List<Path> carpetasExistentes(PieroGCiudadMapa ciudad) {
        return basesExternas.stream().map(base -> base.resolve(ciudad.id()))
                .filter(Files::isDirectory).toList();
    }

    /** {@code true} si el mapa viene dentro del jar (no se puede borrar desde la aplicación). */
    public boolean estaEmpaquetada(PieroGCiudadMapa ciudad) {
        if (!usarClasspath) return false;
        String recurso = "/" + ciudad.carpeta() + "/" + CALLES;
        return existeRecurso(recurso) || existeRecurso(recurso + ".gz");
    }

    /** Ciudades cuyo mapa vive en carpetas externas y no viene en el jar: son las que se pueden eliminar. */
    public List<PieroGCiudadMapa> ciudadesEliminables() {
        List<PieroGCiudadMapa> lista = new ArrayList<>();
        for (PieroGCiudadMapa c : PieroGCiudadMapa.DISPONIBLES) {
            if (!estaEmpaquetada(c) && !carpetasExistentes(c).isEmpty()) lista.add(c);
        }
        lista.addAll(ciudadesImportadas());
        return lista;
    }

    /**
     * Borra del disco todas las carpetas externas de la ciudad. Antes renombra cada carpeta: si
     * algún archivo está bloqueado (p. ej. Windows) el renombrado falla y no se borra nada.
     *
     * @throws MapaEnUsoException si la carpeta está en uso
     * @throws IOException        si no se pudo borrar por otra causa
     */
    public void eliminarCiudad(PieroGCiudadMapa ciudad) throws IOException {
        if (estaEmpaquetada(ciudad)) {
            throw new IOException(ciudad.nombre() + " viene dentro de la aplicación y no se puede eliminar.");
        }
        List<Path> originales = carpetasExistentes(ciudad);
        List<Path> renombradas = new ArrayList<>();
        try {
            for (Path carpeta : originales) {
                Path destino = carpeta.resolveSibling("." + ciudad.id() + ".eliminando");
                borrarArbol(destino); // resto de un intento anterior interrumpido
                Files.move(carpeta, destino);
                renombradas.add(destino);
            }
        } catch (IOException ex) {
            for (int i = 0; i < renombradas.size(); i++) {
                try {
                    Files.move(renombradas.get(i), originales.get(i));
                } catch (IOException ignorada) {
                    // Mejor esfuerzo: la carpeta renombrada queda en disco y se avisa abajo.
                }
            }
            throw new MapaEnUsoException("No se puede eliminar el mapa porque está en uso.", ex);
        }
        for (Path carpeta : renombradas) borrarArbol(carpeta);
    }

    private static void borrarArbol(Path raiz) throws IOException {
        if (!Files.exists(raiz)) return;
        Files.walkFileTree(raiz, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path archivo, java.nio.file.attribute.BasicFileAttributes a)
                    throws IOException {
                Files.delete(archivo);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException error)
                    throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    /** Ciudades con calles en las carpetas externas que no son Juliaca, Puno ni Lima. */
    public List<PieroGCiudadMapa> ciudadesImportadas() {
        Map<String, PieroGCiudadMapa> encontradas = new LinkedHashMap<>();
        for (Path base : basesExternas) {
            if (!Files.isDirectory(base)) continue;
            try (var carpetas = Files.list(base)) {
                carpetas.filter(Files::isDirectory).forEach(carpeta -> {
                    String id = carpeta.getFileName().toString();
                    if (!id.matches("[a-z0-9_-]+") || encontradas.containsKey(id)) return;
                    PieroGCiudadMapa ciudad = PieroGCiudadMapa.personalizada(id, false);
                    if (ciudad.esIncluida()) return;
                    Path plano = carpeta.resolve(CALLES);
                    Path gz = carpeta.resolve(CALLES + ".gz");
                    Path archivo = Files.isRegularFile(plano) ? plano : Files.isRegularFile(gz) ? gz : null;
                    if (archivo == null) return;
                    boolean grande = archivo.toFile().length() > MAPA_GRANDE_BYTES;
                    encontradas.put(id, PieroGCiudadMapa.personalizada(id, grande));
                });
            } catch (IOException ex) {
                // Una carpeta ilegible no debe impedir listar las demás.
            }
        }
        return encontradas.values().stream()
                .sorted(Comparator.comparing(PieroGCiudadMapa::nombre)).toList();
    }

    /** Ciudad incluida o importada con ese id, o vacío si no existe. */
    public Optional<PieroGCiudadMapa> buscarCiudad(String id) {
        if (id == null) return Optional.empty();
        return PieroGCiudadMapa.DISPONIBLES.stream().filter(c -> c.id().equals(id)).findFirst()
                .or(() -> ciudadesImportadas().stream().filter(c -> c.id().equals(id)).findFirst());
    }

    public boolean existe(PieroGCiudadMapa ciudad, String nombreArchivo) {
        return localizar(ciudad, nombreArchivo).isPresent();
    }

    /** Abre el archivo (descomprimiendo si hace falta). El llamador debe cerrarlo. */
    public InputStream abrir(PieroGCiudadMapa ciudad, String nombreArchivo) throws IOException {
        Origen origen = localizar(ciudad, nombreArchivo).orElseThrow(() ->
                new NoSuchFileException(nombreArchivo, null,
                        "No hay " + nombreArchivo + " para " + ciudad.nombre()
                                + ". Búscalo en: " + String.join(" · ", lugaresBuscados(ciudad))));
        InputStream entrada = origen.archivo() != null
                ? Files.newInputStream(origen.archivo())
                : BetoHFuenteMapa.class.getResourceAsStream(origen.recurso());
        if (entrada == null) throw new NoSuchFileException(origen.descripcion());
        entrada = new BufferedInputStream(entrada, 1 << 16);
        return origen.comprimido() ? new GZIPInputStream(entrada, 1 << 16) : entrada;
    }

    /** Carpetas donde se busca, para mensajes de ayuda. */
    public List<String> lugaresBuscados(PieroGCiudadMapa ciudad) {
        List<String> lugares = new ArrayList<>();
        for (Path base : basesExternas) {
            lugares.add(base.resolve(ciudad.id()).toAbsolutePath().toString());
        }
        if (usarClasspath) lugares.add("recursos /" + ciudad.carpeta());
        return lugares;
    }
}
