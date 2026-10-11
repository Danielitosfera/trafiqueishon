package trafiqueishon.simulacion;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import trafiqueishon.geo.GrafoVial;
import trafiqueishon.geo.Pathfinder;
import trafiqueishon.geo.RutaResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Exporta un resumen operativo reproducible usando la ruta optima de Dijkstra. */
public final class ReporteRutaPdf {
    public void generar(RutaMicro servicio, List<PuntoInteres> puntos,
                        GrafoVial grafo, Path destinoArchivo) throws IOException {
        Map<String, PuntoInteres> porId = puntos.stream()
                .collect(Collectors.toMap(PuntoInteres::id, Function.identity()));
        double distanciaMetros = 0;
        StringBuilder detalle = new StringBuilder();
        Pathfinder pathfinder = new Pathfinder(grafo);
        pathfinder.setModo(Pathfinder.Modo.DIJKSTRA);
        for (int i = 0; i < servicio.puntosIntermedios().size(); i++) {
            PuntoInteres punto = porId.get(servicio.puntosIntermedios().get(i));
            if (punto == null) throw new IOException("No se encuentra una parada guardada.");
            detalle.append(i + 1).append(". ").append(punto.nombre())
                    .append(String.format(" | %.6f, %.6f%n", punto.lon(), punto.lat()));
            if (i + 1 < servicio.puntosIntermedios().size()) {
                PuntoInteres siguiente = porId.get(servicio.puntosIntermedios().get(i + 1));
                var inicio = grafo.nodoMasCercanoLonLat(punto.lon(), punto.lat());
                var fin = grafo.nodoMasCercanoLonLat(siguiente.lon(), siguiente.lat());
                RutaResult tramo = pathfinder.ruta(inicio, fin);
                if (tramo == null) throw new IOException("No hay ruta vial para un tramo del servicio.");
                distanciaMetros += tramo.distanciaMetros();
            }
        }

        try (PDDocument documento = new PDDocument()) {
            try (Pagina pagina = new Pagina(documento)) {
                if (servicio.logoPath() != null && !servicio.logoPath().isBlank()
                        && java.nio.file.Files.isRegularFile(Path.of(servicio.logoPath()))) {
                    PDImageXObject logo = PDImageXObject.createFromFile(
                            servicio.logoPath(), documento);
                    pagina.stream.drawImage(logo, 470, pagina.y - 35, 75, 35);
                }
                pagina.escribir("TRAFIQUEISHON - Reporte de ruta optima", 50, 16);
                pagina.escribir("Empresa / linea: " + servicio.nombre(), 50, 12);
                pagina.escribir("Metodo: Dijkstra sobre red vial dirigida", 50, 10);
                pagina.saltoSeccion("Paradas y coordenadas");
                for (String linea : detalle.toString().split("\\R")) pagina.escribir(linea, 55, 9);
                pagina.saltoSeccion("Parametros operativos");
                pagina.escribir("Frecuencia: " + servicio.frecuenciaMinutos() + " minutos", 55, 10);
                pagina.escribir("Unidades activas: " + servicio.unidades(), 55, 10);
                pagina.escribir(String.format("Distancia total: %.2f km", distanciaMetros / 1000),
                        55, 10);
                pagina.escribir("Resumen: La ruta conecta sus paradas en secuencia sobre los tramos "
                        + "dirigidos de menor costo metrico. Validar tiempos, seguridad y demanda "
                        + "con trabajo de campo antes de su operacion.", 50, 9);
            }
            documento.save(destinoArchivo.toFile());
        }
    }

    private String textoSeguro(String texto) {
        return texto.replaceAll("[^\\x20-\\x7E]", " ");
    }

    private final class Pagina implements AutoCloseable {
        private final PDDocument documento;
        private PDPageContentStream stream;
        private float y = 790;

        private Pagina(PDDocument documento) throws IOException {
            this.documento = documento;
            abrir();
        }

        private void abrir() throws IOException {
            PDPage pagina = new PDPage(PDRectangle.A4);
            documento.addPage(pagina);
            stream = new PDPageContentStream(documento, pagina);
            y = 790;
        }

        private void escribir(String texto, float x, int tamano) throws IOException {
            StringBuilder linea = new StringBuilder();
            for (String palabra : textoSeguro(texto).split("\\s+")) {
                if (!linea.isEmpty() && linea.length() + palabra.length() + 1 > 105) {
                    escribirLinea(linea.toString(), x, tamano);
                    linea.setLength(0);
                }
                if (!linea.isEmpty()) linea.append(' ');
                linea.append(palabra);
            }
            if (!linea.isEmpty()) escribirLinea(linea.toString(), x, tamano);
        }

        private void escribirLinea(String texto, float x, int tamano) throws IOException {
            if (y < 70) {
                stream.close();
                abrir();
            }
            stream.beginText();
            stream.setFont(PDType1Font.HELVETICA, tamano);
            stream.newLineAtOffset(x, y);
            stream.showText(texto);
            stream.endText();
            y -= tamano + 5;
        }

        private void saltoSeccion(String titulo) throws IOException {
            y -= 10;
            escribir(titulo, 50, 12);
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }
}
