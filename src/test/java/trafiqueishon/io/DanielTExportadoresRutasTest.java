package trafiqueishon.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGNodo;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.ruta.LuisYTrayecto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DanielTExportadoresRutasTest {
    @TempDir
    Path temporal;

    @Test
    void exportaVariasRutasComoFeaturesGpxYCsv() throws Exception {
        DanielTRutaExportable ida = ruta("Micro Azul · Ida", -70.0, "#00A8FF");
        DanielTRutaExportable vuelta = ruta("Micro Azul · Vuelta", -69.999, "#FF1744");

        Path geoJson = temporal.resolve("rutas.geojson");
        new DanielTExportadorGeoJson().exportar(List.of(ida, vuelta), geoJson);
        assertEquals(2, new ObjectMapper().readTree(geoJson.toFile())
                .get("features").size());

        Path gpx = temporal.resolve("rutas.gpx");
        new DanielTExportadorGpx().exportar(List.of(ida, vuelta), gpx);
        assertEquals(2, Files.readString(gpx).split("<trk>", -1).length - 1);

        Path csv = temporal.resolve("rutas.csv");
        new DanielTExportadorCsv().exportar(List.of(ida, vuelta), csv);
        String contenidoCsv = Files.readString(csv);
        assertTrue(contenidoCsv.contains("Micro Azul · Ida"));
        assertTrue(contenidoCsv.contains("Micro Azul · Vuelta"));
        assertEquals(9, contenidoCsv.lines().toList().getLast().split(",", -1).length);
    }

    @Test
    void pdfPorMicroGeneraUnaLaminaPorServicio() throws Exception {
        Path pdf = temporal.resolve("rutas.pdf");
        List<DanielTRutaExportable> rutas = java.util.stream.IntStream.range(0, 9)
                .mapToObj(i -> ruta("Micro " + i + " · Ida", -70.0 + i * 0.001,
                        i % 2 == 0 ? "#00A8FF" : "#FF1744"))
                .toList();
        new DanielTExportadorPdf().exportar(rutas, pdf);

        try (PDDocument documento = PDDocument.load(pdf.toFile())) {
            assertEquals(9, documento.getNumberOfPages());
            String texto = new PDFTextStripper().getText(documento);
            assertTrue(texto.contains("MICRO 0"));
            assertTrue(texto.contains("INICIO"));
            assertTrue(texto.contains("FINAL"));
            assertTrue(Files.size(pdf) > 0);
        }
    }

    @Test
    void pdfVistaMultipleDibujaTodasLasRutasEnUnaSolaLamina() throws Exception {
        Path pdf = temporal.resolve("multiple.pdf");
        List<DanielTRutaExportable> rutas = List.of(
                ruta("Micro A · Ida", -70.000, "#39FF14"),
                ruta("Micro A · Vuelta", -70.000, "#39FF14"),
                ruta("Micro B · Ida", -70.002, "#FF00A8"));
        new DanielTExportadorPdf().exportarVistaMultiple(rutas, "Rutas de Juliaca", pdf);

        try (PDDocument documento = PDDocument.load(pdf.toFile())) {
            assertEquals(1, documento.getNumberOfPages());
            String texto = new PDFTextStripper().getText(documento);
            assertTrue(texto.contains("RUTAS INCLUIDAS (2)"), texto);
            assertTrue(texto.contains("MICRO A"));
            assertTrue(texto.contains("MICRO B"));
            assertTrue(texto.contains("VUELTA: DISCONTINUA"));
        }
    }

    @Test
    void pdfVistaIndividualMuestraIdaYVueltaDeUnaMicro() throws Exception {
        Path pdf = temporal.resolve("individual.pdf");
        new DanielTExportadorPdf().exportarVistaIndividual(
                ruta("Micro C · Ida", -70.0, "#123456"),
                ruta("Micro C · Vuelta", -70.0, "#123456"), pdf);

        try (PDDocument documento = PDDocument.load(pdf.toFile())) {
            assertEquals(1, documento.getNumberOfPages());
            String texto = new PDFTextStripper().getText(documento);
            assertTrue(texto.contains("MICRO C"));
            assertTrue(texto.contains("PARTIDA (IDA)"));
            assertTrue(texto.contains("RETORNO (VUELTA)"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new DanielTExportadorPdf().exportarVistaIndividual(null, null, pdf));
    }

    private DanielTRutaExportable ruta(String nombre, double lon, String color) {
        PieroGNodo a = new PieroGNodo(0, 0, 0, lon, -15);
        PieroGNodo b = new PieroGNodo(1, 1, 0, lon + 0.001, -15);
        PieroGEdge edge = new PieroGEdge(0, a, b, 100, "residential",
                "Avenida", 30, false, 1, null);
        LuisYTrayecto trayecto = new LuisYTrayecto(nombre, nombre, "Empresa",
                List.of(new LuisYPuntoImportante("a", "Inicio", lon, -15),
                        new LuisYPuntoImportante("b", "Final", lon + 0.001, -15)),
                List.of(edge), 100, edge.costoBase, "SERVICIO");
        return new DanielTRutaExportable(nombre, color, trayecto);
    }
}
