package trafiqueishon.io;

import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGNodo;
import trafiqueishon.ruta.LuisYPaletaColores;
import trafiqueishon.ruta.LuisYTrayecto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class DanielTExportadorGpx {

    public void exportar(LuisYTrayecto trayecto, Path destino) throws IOException {
        exportar(List.of(new DanielTRutaExportable(trayecto.nombre(), LuisYPaletaColores.COLOR_RUTA_IDA, trayecto)), destino);
    }

    public void exportar(List<DanielTRutaExportable> rutas, Path destino) throws IOException {
        if (rutas.isEmpty()) throw new IllegalArgumentException("Selecciona al menos una ruta.");
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"Trafiqueishon\" ");
        sb.append("xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        for (DanielTRutaExportable ruta : rutas) {
            sb.append("  <trk>\n");
            sb.append("    <name>").append(escape(ruta.nombre())).append("</name>\n");
            sb.append("    <trkseg>\n");
            for (PieroGEdge e : ruta.trayecto().edges()) {
                PieroGNodo n = e.origen();
                sb.append("      <trkpt lat=\"").append(n.lat)
                        .append("\" lon=\"").append(n.lon).append("\"/>\n");
            }
            if (!ruta.trayecto().edges().isEmpty()) {
                PieroGNodo ultimo = ruta.trayecto().edges().getLast().destino();
                sb.append("      <trkpt lat=\"").append(ultimo.lat)
                        .append("\" lon=\"").append(ultimo.lon).append("\"/>\n");
            }
            sb.append("    </trkseg>\n");
            sb.append("  </trk>\n");
        }
        sb.append("</gpx>\n");
        Files.writeString(destino, sb.toString());
    }

    private String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}