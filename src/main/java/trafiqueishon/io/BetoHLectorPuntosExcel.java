package trafiqueishon.io;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import trafiqueishon.ruta.LuisYPuntoImportante;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class BetoHLectorPuntosExcel {

    public List<LuisYPuntoImportante> leer(Path archivo) throws IOException {
        try (InputStream in = Files.newInputStream(archivo);
             Workbook libro = new XSSFWorkbook(in)) {
            Sheet hoja = libro.getSheet("Puntos");
            if (hoja == null) throw new IOException("El Excel debe tener una hoja 'Puntos'.");
            DataFormatter fmt = new DataFormatter();
            List<LuisYPuntoImportante> lista = new ArrayList<>();
            for (int i = 1; i <= hoja.getLastRowNum(); i++) {
                Row row = hoja.getRow(i);
                if (row == null) continue;
                String id = fmt.formatCellValue(row.getCell(0)).trim();
                String nombre = fmt.formatCellValue(row.getCell(1)).trim();
                String lonTxt = fmt.formatCellValue(row.getCell(2)).trim();
                String latTxt = fmt.formatCellValue(row.getCell(3)).trim();
                String tipoTxt = fmt.formatCellValue(row.getCell(4)).trim();
                if (id.isEmpty() || lonTxt.isEmpty() || latTxt.isEmpty()) continue;
                double lon = Double.parseDouble(lonTxt.replace(',', '.'));
                double lat = Double.parseDouble(latTxt.replace(',', '.'));
                LuisYPuntoImportante.TipoPunto tipo = LuisYPuntoImportante.TipoPunto.desdeTexto(tipoTxt);
                lista.add(new LuisYPuntoImportante(id, nombre.isEmpty() ? id : nombre, lon, lat, tipo));
            }
            return List.copyOf(lista);
        }
    }
}