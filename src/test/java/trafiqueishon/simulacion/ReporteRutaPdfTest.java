package trafiqueishon.simulacion;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trafiqueishon.geo.GrafoVial;
import trafiqueishon.geo.Proyector;
import trafiqueishon.geo.Via;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReporteRutaPdfTest {
    @TempDir
    Path directorio;

    @Test
    void exportsStopsCoordinatesOperationalDataAndDijkstraDistance() throws Exception {
        List<PuntoInteres> puntos = List.of(
                new PuntoInteres("o", "Origen Central", -70.0, -15.0),
                new PuntoInteres("d", "Destino Norte", -69.999, -15.0));
        Via via = new Via();
        via.highway = "primary";
        via.coords = List.of(new double[]{-70.0, -15.0}, new double[]{-69.999, -15.0});
        Proyector proyector = new Proyector();
        proyector.preProyectar(List.of(via));
        GrafoVial grafo = new GrafoVial();
        grafo.construir(List.of(via), proyector);
        RutaMicro ruta = new RutaMicro("Linea Centro", List.of("o", "d"), 8, 12);
        Path archivo = directorio.resolve("ruta.pdf");

        new ReporteRutaPdf().generar(ruta, puntos, grafo, archivo);

        try (PDDocument pdf = PDDocument.load(archivo.toFile())) {
            String texto = new PDFTextStripper().getText(pdf);
            assertTrue(texto.contains("Linea Centro"));
            assertTrue(texto.contains("Origen Central"));
            assertTrue(texto.contains("Destino Norte"));
            assertTrue(texto.contains("Frecuencia: 8 minutos"));
            assertTrue(texto.contains("Unidades activas: 12"));
            assertTrue(texto.contains("Distancia total:"));
        }
    }
}
