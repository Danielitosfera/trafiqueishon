package trafiqueishon.io;

import trafiqueishon.geo.PieroGCiudadMapa;
import trafiqueishon.ruta.LuisYPuntoImportante;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Genera el {@code puntos.json} de una ciudad a partir de su propio {@code calles.geojson}
 * (descargado de Overpass). No toca la UI: quien lo llame solo debe volver a leer los puntos
 * con {@link BetoHFuenteMapa} después.
 */
public final class DanielTImportadorMapa {

    /**
     * @param guardado    archivo {@code puntos.json} escrito (null si no se encontró nada)
     * @param encontrados puntos de interés hallados en las calles; es también el total guardado
     */
    public record Resultado(Path guardado, int encontrados) {}

    private final BetoHFuenteMapa fuente;
    private final BetoHLectorPuntosGeoJson lectorGeoJson = new BetoHLectorPuntosGeoJson();
    private final BetoHLectorPuntosJson lectorJson = new BetoHLectorPuntosJson();

    public DanielTImportadorMapa(BetoHFuenteMapa fuente) {
        this.fuente = fuente;
    }

    /**
     * Copia un GeoJSON crudo de Overpass a la carpeta del usuario como {@code calles.geojson} de
     * una ciudad nueva (sin tocar nunca los recursos del jar). El archivo debe ser un
     * {@code FeatureCollection}; esa comprobación mira solo el inicio, así no se lee un archivo
     * de cientos de MB entero. Si falla, no queda un mapa a medias.
     *
     * @throws IllegalArgumentException si el nombre no da un id válido o choca con Juliaca, Puno o Lima
     * @throws IOException              si el archivo no es un GeoJSON o no se pudo copiar
     */
    public PieroGCiudadMapa importarCiudad(Path geojsonCrudo, String nombre) throws IOException {
        String id = PieroGCiudadMapa.idDesdeNombre(nombre);
        if (id.isEmpty()) throw new IllegalArgumentException("Escribe un nombre para la ciudad.");
        if (PieroGCiudadMapa.personalizada(id, false).esIncluida()) {
            throw new IllegalArgumentException("«" + nombre.trim() + "» ya viene incluida en la aplicación.");
        }
        validarGeoJson(geojsonCrudo);
        PieroGCiudadMapa ciudad = PieroGCiudadMapa.personalizada(id,
                Files.size(geojsonCrudo) > (40L << 20));
        Path carpeta = fuente.carpetaUsuario(ciudad);
        Files.createDirectories(carpeta);
        Path destino = carpeta.resolve(BetoHFuenteMapa.CALLES);
        Path temporal = carpeta.resolve(BetoHFuenteMapa.CALLES + ".tmp");
        try {
            Files.copy(geojsonCrudo, temporal, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporal);
        }
        return ciudad;
    }

    private static void validarGeoJson(Path archivo) throws IOException {
        byte[] inicio;
        try (InputStream in = Files.newInputStream(archivo)) {
            inicio = in.readNBytes(1 << 16);
        }
        String texto = new String(inicio, java.nio.charset.StandardCharsets.UTF_8).stripLeading();
        if (!texto.startsWith("{") || !texto.contains("\"FeatureCollection\"")) {
            throw new IOException("«" + archivo.getFileName() + "» no es un GeoJSON (FeatureCollection). "
                    + "Exporta desde overpass-turbo como GeoJSON, no como JSON crudo.");
        }
    }

    /**
     * Lee el {@code calles.geojson} de la ciudad (carpeta externa, recurso del jar o {@code .gz}),
     * extrae los puntos de interés ({@code amenity}/{@code tourism} con nombre) y SOBREESCRIBE su
     * {@code puntos.json}. Si el GeoJSON no trae puntos no se escribe nada y {@code encontrados}
     * vale 0, para no borrar los puntos existentes con un archivo vacío.
     */
    public Resultado extraerDeCalles(PieroGCiudadMapa ciudad) throws IOException {
        List<LuisYPuntoImportante> encontrados;
        try (InputStream entrada = fuente.abrir(ciudad, BetoHFuenteMapa.CALLES)) {
            encontrados = lectorGeoJson.leer(entrada);
        }
        if (encontrados.isEmpty()) return new Resultado(null, 0);

        Path destino = fuente.carpetaEscritura(ciudad).resolve(BetoHFuenteMapa.PUNTOS);
        Path temporal = destino.resolveSibling(BetoHFuenteMapa.PUNTOS + ".tmp");
        lectorJson.guardar(encontrados, temporal); // si falla, el puntos.json anterior queda intacto
        Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING);
        return new Resultado(destino, encontrados.size());
    }
}
