package trafiqueishon.io;

import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGNodo;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.ruta.LuisYTrayecto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Exporta el recorrido de una ruta como texto plano: cada punto por el que
 * pasa, en orden y con sus coordenadas (x = longitud, y = latitud).
 */
public final class DanielTExportadorTxt {

    private static final String DOBLE = "=".repeat(44);
    private static final String SIMPLE = "-".repeat(44);
    private static final int ANCHO_NOMBRE = 26;

    /** Exporta un recorrido de partida y, opcionalmente, uno de retorno. */
    public void exportar(LuisYTrayecto partida, LuisYTrayecto retorno, Path destino) throws IOException {
        Objects.requireNonNull(partida, "El recorrido de partida es obligatorio.");
        List<DanielTRutaExportable> rutas = new ArrayList<>();
        rutas.add(new DanielTRutaExportable(partida.nombre(), "#000000", partida));
        if (retorno != null) rutas.add(new DanielTRutaExportable(retorno.nombre(), "#000000", retorno));
        exportar(rutas, destino);
    }

    public void exportar(List<DanielTRutaExportable> rutas, Path destino) throws IOException {
        if (rutas == null || rutas.isEmpty()) {
            throw new IllegalArgumentException("Selecciona al menos una ruta.");
        }
        Files.writeString(destino, generar(rutas), StandardCharsets.UTF_8);
    }

    /** Devuelve el texto del informe sin escribirlo a disco. */
    public String generar(List<DanielTRutaExportable> rutas) {
        StringBuilder sb = new StringBuilder();
        sb.append(DOBLE).append('\n');
        sb.append("TRAFIQUEISHON - RECORRIDO DE RUTA\n");
        sb.append(DOBLE).append('\n');
        for (int i = 0; i < rutas.size(); i++) {
            if (i > 0) sb.append(SIMPLE).append('\n');
            agregarRecorrido(sb, rutas.get(i));
        }
        sb.append(DOBLE).append('\n');
        return sb.toString();
    }

    private void agregarRecorrido(StringBuilder sb, DanielTRutaExportable ruta) {
        LuisYTrayecto trayecto = ruta.trayecto();
        boolean retorno = "RETORNO".equalsIgnoreCase(trayecto.sentido());
        sb.append(retorno ? "RECORRIDO DE RETORNO" : "RECORRIDO DE PARTIDA").append('\n');
        sb.append("RUTA: ").append(ruta.nombre().toUpperCase(Locale.ROOT)).append('\n');

        List<String[]> filas = puntos(trayecto);
        int n = 0;
        for (String[] fila : filas) {
            sb.append(String.format(Locale.ROOT, "%d. %-" + ANCHO_NOMBRE + "s (x=%s, y=%s)%n",
                    ++n, fila[0], fila[1], fila[2]));
        }
        sb.append("Total: ").append(n).append(" puntos\n");
    }

    /**
     * Puntos del recorrido en orden. Usa los puntos nombrados del trayecto; si
     * no los tiene, recorre los nodos de sus tramos con el nombre de la calle.
     */
    private List<String[]> puntos(LuisYTrayecto trayecto) {
        List<String[]> filas = new ArrayList<>();
        if (!trayecto.puntos().isEmpty()) {
            for (LuisYPuntoImportante p : trayecto.puntos()) {
                filas.add(new String[]{p.nombre().toUpperCase(Locale.ROOT),
                        coordenada(p.lon()), coordenada(p.lat())});
            }
            return filas;
        }
        List<PieroGEdge> edges = trayecto.edges();
        if (edges.isEmpty()) return filas;
        PieroGNodo primero = edges.getFirst().origen();
        filas.add(fila(nombreCalle(edges.getFirst()), primero));
        for (PieroGEdge e : edges) filas.add(fila(nombreCalle(e), e.destino()));
        return filas;
    }

    private static String[] fila(String nombre, PieroGNodo nodo) {
        return new String[]{nombre, coordenada(nodo.lon), coordenada(nodo.lat)};
    }

    private static String nombreCalle(PieroGEdge e) {
        return e.name == null || e.name.isBlank() ? "SIN NOMBRE" : e.name.toUpperCase(Locale.ROOT);
    }

    private static String coordenada(double valor) {
        return String.format(Locale.ROOT, "%.5f", valor);
    }
}
