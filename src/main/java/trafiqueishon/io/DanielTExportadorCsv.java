package trafiqueishon.io;

import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGNodo;
import trafiqueishon.ruta.LuisYPaletaColores;
import trafiqueishon.ruta.LuisYTrayecto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class DanielTExportadorCsv {

    public void exportar(LuisYTrayecto trayecto, Path destino) throws IOException {
        exportar(List.of(new DanielTRutaExportable(trayecto.nombre(), LuisYPaletaColores.COLOR_RUTA_IDA, trayecto)), destino);
    }

    public void exportar(List<DanielTRutaExportable> rutas, Path destino) throws IOException {
        if (rutas.isEmpty()) throw new IllegalArgumentException("Selecciona al menos una ruta.");
        StringBuilder sb = new StringBuilder();
        sb.append("ruta,sentido,color,orden,lon,lat,calle,highway,longitud_m\n");
        for (DanielTRutaExportable ruta : rutas) {
            int i = 0;
            for (PieroGEdge e : ruta.trayecto().edges()) {
                PieroGNodo n = e.origen();
                sb.append(escape(ruta.nombre())).append(',')
                        .append(escape(ruta.trayecto().sentido())).append(',')
                        .append(escape(ruta.color())).append(',')
                        .append(++i).append(',')
                        .append(n.lon).append(',')
                        .append(n.lat).append(',')
                        .append(escape(e.name)).append(',')
                        .append(escape(e.highway)).append(',')
                        .append(String.format(Locale.ROOT, "%.2f", e.longitudMetros()))
                        .append('\n');
            }
            if (!ruta.trayecto().edges().isEmpty()) {
                PieroGNodo ultimo = ruta.trayecto().edges().getLast().destino();
                sb.append(escape(ruta.nombre())).append(',')
                        .append(escape(ruta.trayecto().sentido())).append(',')
                        .append(escape(ruta.color())).append(',')
                        .append(++i).append(',')
                        .append(ultimo.lon).append(',')
                        .append(ultimo.lat).append(",,,0\n");
            }
        }
        Files.writeString(destino, sb.toString());
    }

    private String escape(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}