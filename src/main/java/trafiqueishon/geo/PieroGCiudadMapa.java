package trafiqueishon.geo;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Ciudad disponible en el selector. Cada ciudad tiene su carpeta
 * {@code mapas/<id>/} con {@code calles.geojson} (solo geometrías de calles) y
 * {@code puntos.json} (puntos de interés que se dibujan encima).
 *
 * @param id     nombre de carpeta (minúsculas, sin espacios)
 * @param nombre nombre visible
 * @param grande {@code true} si el mapa es masivo y conviene cargarlo con LOD agresivo
 */
public record PieroGCiudadMapa(String id, String nombre, boolean grande) {

    public static final PieroGCiudadMapa JULIACA = new PieroGCiudadMapa("juliaca", "Juliaca", false);
    public static final PieroGCiudadMapa PUNO = new PieroGCiudadMapa("puno", "Puno", false);
    public static final PieroGCiudadMapa LIMA = new PieroGCiudadMapa("lima", "Lima", true);

    /** Ciudades que ofrece el selector, en orden. */
    public static final List<PieroGCiudadMapa> DISPONIBLES = List.of(JULIACA, PUNO, LIMA);

    /** Ítem especial del selector (no es una ciudad): abre la importación de un mapa nuevo. */
    public static final PieroGCiudadMapa IMPORTAR =
            new PieroGCiudadMapa("importar-mapa", "➕ Importar nuevo mapa (GeoJSON)", false);

    public PieroGCiudadMapa {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nombre, "nombre");
        if (!id.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("Id de ciudad inválido: " + id);
        }
    }

    /** Busca una ciudad por id; si no existe devuelve Juliaca. */
    public static PieroGCiudadMapa porId(String id) {
        if (id == null) return JULIACA;
        String buscado = id.trim().toLowerCase(Locale.ROOT);
        return DISPONIBLES.stream().filter(c -> c.id.equals(buscado)).findFirst().orElse(JULIACA);
    }

    public boolean esImportar() {
        return this.equals(IMPORTAR);
    }

    /** {@code true} para Juliaca, Puno y Lima, cuyos mapas vienen dentro de la aplicación. */
    public boolean esIncluida() {
        return DISPONIBLES.stream().anyMatch(c -> c.id.equals(id));
    }

    /** Ciudad importada por el usuario, identificada por el nombre de su carpeta. */
    public static PieroGCiudadMapa personalizada(String id, boolean grande) {
        StringBuilder nombre = new StringBuilder();
        for (String palabra : id.split("[-_]+")) {
            if (palabra.isEmpty()) continue;
            if (!nombre.isEmpty()) nombre.append(' ');
            nombre.append(Character.toUpperCase(palabra.charAt(0))).append(palabra.substring(1));
        }
        return new PieroGCiudadMapa(id, nombre.isEmpty() ? id : nombre.toString(), grande);
    }

    /**
     * Convierte el nombre escrito por el usuario en un id de carpeta: minúsculas, sin tildes y
     * con guiones en lugar de espacios o símbolos. Devuelve cadena vacía si no queda nada.
     */
    public static String idDesdeNombre(String nombre) {
        if (nombre == null) return "";
        String sinTildes = Normalizer.normalize(nombre.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return sinTildes.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    /** Ruta de carpeta relativa: {@code mapas/<id>}. */
    public String carpeta() {
        return "mapas/" + id;
    }

    @Override
    public String toString() {
        return nombre;
    }
}
