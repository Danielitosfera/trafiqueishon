package trafiqueishon.simulacion;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImportadorExcelEmpresasTest {
    @TempDir
    Path temporal;

    @Test
    void importsOrderedStopsPerCompanyAndSkipsBlankSheets() throws Exception {
        Path archivo = temporal.resolve("empresas.xlsx");
        try (XSSFWorkbook libro = new XSSFWorkbook();
             OutputStream salida = Files.newOutputStream(archivo)) {
            Sheet empresa = libro.createSheet("Transportes Sol");
            ponerCabecera(empresa.createRow(0));
            Row ruta = empresa.createRow(1);
            ruta.createCell(0).setCellValue("Línea centro");
            ruta.createCell(1).setCellValue("Plaza de Armas");
            ruta.createCell(2).setCellValue("Terminal terrestre");
            ruta.createCell(3).setCellValue("Mercado San José, Universidad");
            ruta.createCell(4).setCellValue("Micro");
            ruta.createCell(5).setCellFormula("4+4");
            ruta.createCell(6).setCellValue(12);

            Sheet vacia = libro.createSheet("Sin servicio");
            ponerCabecera(vacia.createRow(0));
            Sheet conError = libro.createSheet("Empresa con error");
            ponerCabecera(conError.createRow(0));
            Row invalida = conError.createRow(1);
            invalida.createCell(0).setCellValue("Ruta inválida");
            invalida.createCell(1).setCellValue("Punto inexistente");
            invalida.createCell(2).setCellValue("Terminal terrestre");
            invalida.createCell(3).setCellValue("");
            invalida.createCell(4).setCellValue("Micro");
            invalida.createCell(5).setCellValue(10);
            invalida.createCell(6).setCellValue(4);
            libro.write(salida);
        }

        ImportadorExcelEmpresas.ResultadoImportacion resultado =
                new ImportadorExcelEmpresas().importar(archivo, puntos());

        assertEquals(1, resultado.rutas().size());
        RutaMicro ruta = resultado.rutas().getFirst();
        assertEquals("Transportes Sol", ruta.empresa());
        assertEquals(List.of("plaza", "mercado", "universidad", "terminal"),
                ruta.puntosIntermedios());
        assertEquals(8, ruta.frecuenciaMinutos());
        assertEquals(List.of("Sin servicio"), resultado.hojasOmitidas());
        assertEquals(1, resultado.errores().size());
        assertTrue(resultado.errores().getFirst().contains("Punto inexistente"));
    }

    @Test
    void createsAnImportableBlankTemplateAndResolvesAccents() throws Exception {
        Path plantilla = temporal.resolve("plantilla.xlsx");
        ImportadorExcelEmpresas importador = new ImportadorExcelEmpresas();
        importador.crearPlantilla(plantilla);

        var resultado = importador.importar(plantilla, puntos());

        assertTrue(resultado.rutas().isEmpty());
        assertEquals(List.of("Empresa 1"), resultado.hojasOmitidas());
        assertTrue(resultado.errores().isEmpty());
    }

    private static void ponerCabecera(Row fila) {
        String[] valores = {"Ruta", "Origen", "Destino", "Puntos intermedios",
                "Tipo de vehículo", "Frecuencia (minutos)", "Unidades"};
        for (int i = 0; i < valores.length; i++) fila.createCell(i).setCellValue(valores[i]);
    }

    private static List<PuntoInteres> puntos() {
        return List.of(
                new PuntoInteres("plaza", "Plaza de Armas", -70.13, -15.49),
                new PuntoInteres("mercado", "Mercado San José", -70.14, -15.49),
                new PuntoInteres("universidad", "Universidad", -70.12, -15.49),
                new PuntoInteres("terminal", "Terminal terrestre", -70.11, -15.49));
    }
}
