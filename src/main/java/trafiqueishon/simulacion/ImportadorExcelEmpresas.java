package trafiqueishon.simulacion;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Lee hojas de empresa con rutas de micro y paradas obligatorias ordenadas. */
public final class ImportadorExcelEmpresas {
    private static final Pattern SEPARADOR_WAYPOINT =
            Pattern.compile("\\s*(?:,|;|\\s+-\\s+|\\s*>\\s*)\\s*");
    private static final List<String> CABECERAS = List.of(
            "Ruta", "Origen", "Destino", "Puntos intermedios",
            "Tipo de vehiculo", "Frecuencia (minutos)", "Unidades");
    private static final Map<String, Set<String>> ALIAS_CABECERAS = Map.of(
            "ruta", Set.of("ruta", "nombre", "nombre ruta", "linea"),
            "origen", Set.of("origen", "terminal origen"),
            "destino", Set.of("destino", "terminal destino"),
            "waypoints", Set.of("puntos intermedios", "puntosintermedios",
                    "waypoints", "paradas", "paradas intermedias"),
            "tipo", Set.of("tipo", "tipo vehiculo", "tipo de vehiculo", "vehiculo", "clase"),
            "frecuencia", Set.of("frecuencia", "frecuencia minutos", "minutos frecuencia"),
            "unidades", Set.of("unidades", "flota", "cantidad"));

    public ResultadoImportacion importar(Path archivo, List<PuntoInteres> puntos)
            throws IOException {
        if (archivo == null || !Files.isRegularFile(archivo)
                || !archivo.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IOException("Selecciona un archivo Excel valido con extension .xlsx.");
        }
        if (puntos == null || puntos.size() < 2) {
            throw new IOException("El escenario requiere al menos dos puntos de interes.");
        }
        Map<String, String> idsPorAlias = indexarPuntos(puntos);
        DataFormatter formato = new DataFormatter(Locale.forLanguageTag("es-PE"));
        List<RutaMicro> rutas = new ArrayList<>();
        List<String> errores = new ArrayList<>();
        List<String> hojasOmitidas = new ArrayList<>();

        try (InputStream entrada = Files.newInputStream(archivo);
             Workbook libro = WorkbookFactory.create(entrada)) {
            FormulaEvaluator evaluador = libro.getCreationHelper().createFormulaEvaluator();
            for (Sheet hoja : libro) {
                List<Integer> filasNoVacias = filasNoVacias(hoja, formato, evaluador);
                if (filasNoVacias.isEmpty()) {
                    hojasOmitidas.add(hoja.getSheetName());
                    continue;
                }
                int filaCabecera = filasNoVacias.getFirst();
                Map<String, Integer> columnas = leerCabeceras(
                        hoja.getRow(filaCabecera), formato, evaluador);
                List<String> faltantes = List.of("ruta", "origen", "destino", "waypoints",
                        "tipo", "frecuencia", "unidades").stream()
                        .filter(clave -> !columnas.containsKey(clave)).toList();
                if (!faltantes.isEmpty()) {
                    errores.add("Hoja '" + hoja.getSheetName()
                            + "': faltan columnas requeridas: " + String.join(", ", faltantes));
                    continue;
                }
                if (filasNoVacias.stream().noneMatch(indice -> indice > filaCabecera)) {
                    hojasOmitidas.add(hoja.getSheetName());
                    continue;
                }
                String empresa = hoja.getSheetName().trim();
                if (empresa.isBlank()) {
                    errores.add("Una hoja tiene el nombre de empresa vacio.");
                    continue;
                }
                for (int indiceFila : filasNoVacias) {
                    if (indiceFila <= filaCabecera) continue;
                    Row fila = hoja.getRow(indiceFila);
                    try {
                        rutas.add(leerRuta(
                                fila, columnas, formato, evaluador, empresa, idsPorAlias));
                    } catch (IllegalArgumentException ex) {
                        errores.add("Hoja '" + empresa + "', fila " + (indiceFila + 1)
                                + ": " + ex.getMessage());
                    }
                }
            }
        } catch (org.apache.poi.EncryptedDocumentException ex) {
            throw new IOException("El libro Excel esta cifrado y no se puede importar.", ex);
        } catch (IOException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IOException("No se pudo leer el libro .xlsx: " + ex.getMessage(), ex);
        }
        return new ResultadoImportacion(rutas, hojasOmitidas, errores);
    }

    public void crearPlantilla(Path destino) throws IOException {
        if (destino == null || !destino.getFileName().toString()
                .toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IOException("La plantilla debe guardarse con extension .xlsx.");
        }
        try (Workbook libro = new XSSFWorkbook();
             OutputStream salida = Files.newOutputStream(destino)) {
            Sheet hoja = libro.createSheet("Empresa 1");
            Row cabecera = hoja.createRow(0);
            for (int i = 0; i < CABECERAS.size(); i++) {
                cabecera.createCell(i).setCellValue(CABECERAS.get(i));
                hoja.setColumnWidth(i, 24 * 256);
            }
            hoja.createFreezePane(0, 1);
            libro.write(salida);
        }
    }

    private RutaMicro leerRuta(Row fila, Map<String, Integer> columnas, DataFormatter formato,
                               FormulaEvaluator evaluador, String empresa,
                               Map<String, String> idsPorAlias) {
        String nombre = valor(fila, columnas.get("ruta"), formato, evaluador);
        String origenTexto = valor(fila, columnas.get("origen"), formato, evaluador);
        String destinoTexto = valor(fila, columnas.get("destino"), formato, evaluador);
        String waypointsTexto = valor(fila, columnas.get("waypoints"), formato, evaluador);
        String tipo = normalizar(valor(fila, columnas.get("tipo"), formato, evaluador));
        if (nombre.isBlank()) throw new IllegalArgumentException("falta el nombre de la ruta.");
        if (origenTexto.isBlank()) throw new IllegalArgumentException("falta el origen.");
        if (destinoTexto.isBlank()) throw new IllegalArgumentException("falta el destino.");
        if (!Set.of("micro", "micros", "microbus", "microbuses",
                "transporte publico", "publico").contains(tipo)) {
            throw new IllegalArgumentException(
                    "el tipo de vehiculo debe ser Micro o Transporte publico.");
        }

        List<String> puntos = new ArrayList<>();
        puntos.add(resolverPunto(origenTexto, idsPorAlias));
        if (!waypointsTexto.isBlank()) {
            for (String waypoint : SEPARADOR_WAYPOINT.split(waypointsTexto)) {
                if (!waypoint.isBlank()) puntos.add(resolverPunto(waypoint, idsPorAlias));
            }
        }
        puntos.add(resolverPunto(destinoTexto, idsPorAlias));
        for (int i = 1; i < puntos.size(); i++) {
            if (puntos.get(i).equals(puntos.get(i - 1))) {
                throw new IllegalArgumentException(
                        "hay dos puntos consecutivos iguales en el recorrido.");
            }
        }
        int frecuencia = enteroPositivo(
                valor(fila, columnas.get("frecuencia"), formato, evaluador), "frecuencia");
        int unidades = enteroPositivo(
                valor(fila, columnas.get("unidades"), formato, evaluador), "unidades");
        return new RutaMicro(nombre, empresa, puntos, frecuencia, unidades, "");
    }

    private int enteroPositivo(String texto, String etiqueta) {
        if (texto.isBlank()) throw new IllegalArgumentException("falta " + etiqueta + ".");
        try {
            int numero = new BigDecimal(texto.replace(',', '.')).intValueExact();
            if (numero < 1) throw new ArithmeticException();
            return numero;
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new IllegalArgumentException(etiqueta + " debe ser un entero positivo.");
        }
    }

    private Map<String, String> indexarPuntos(List<PuntoInteres> puntos) throws IOException {
        Map<String, String> resultado = new HashMap<>();
        for (PuntoInteres punto : puntos) {
            registrarAlias(resultado, punto.id(), punto.id());
            registrarAlias(resultado, punto.nombre(), punto.id());
        }
        return Map.copyOf(resultado);
    }

    private void registrarAlias(Map<String, String> alias, String nombre, String id)
            throws IOException {
        String clave = normalizar(nombre);
        String previo = alias.putIfAbsent(clave, id);
        if (previo != null && !previo.equals(id)) {
            throw new IOException("Hay nombres de puntos ambiguos en el escenario: " + nombre);
        }
    }

    private String resolverPunto(String texto, Map<String, String> idsPorAlias) {
        String id = idsPorAlias.get(normalizar(texto));
        if (id == null) {
            throw new IllegalArgumentException("no existe el punto de interes '" + texto.trim() + "'.");
        }
        return id;
    }

    private Map<String, Integer> leerCabeceras(
            Row fila, DataFormatter formato, FormulaEvaluator evaluador) {
        Map<String, Integer> columnas = new LinkedHashMap<>();
        for (int i = 0; i < fila.getLastCellNum(); i++) {
            String encabezado = normalizar(valor(fila, i, formato, evaluador));
            for (Map.Entry<String, Set<String>> alias : ALIAS_CABECERAS.entrySet()) {
                if (alias.getValue().contains(encabezado)) {
                    columnas.putIfAbsent(alias.getKey(), i);
                    break;
                }
            }
        }
        return columnas;
    }

    private List<Integer> filasNoVacias(
            Sheet hoja, DataFormatter formato, FormulaEvaluator evaluador) {
        List<Integer> filas = new ArrayList<>();
        for (int i = hoja.getFirstRowNum(); i <= hoja.getLastRowNum(); i++) {
            Row fila = hoja.getRow(i);
            if (fila == null) continue;
            boolean tieneContenido = false;
            for (int celda = fila.getFirstCellNum(); celda >= 0
                    && celda < fila.getLastCellNum(); celda++) {
                if (!valor(fila, celda, formato, evaluador).isBlank()) {
                    tieneContenido = true;
                    break;
                }
            }
            if (tieneContenido) filas.add(i);
        }
        return filas;
    }

    private String valor(Row fila, Integer columna, DataFormatter formato,
                         FormulaEvaluator evaluador) {
        if (fila == null || columna == null || fila.getCell(columna) == null) return "";
        return formato.formatCellValue(fila.getCell(columna), evaluador).trim();
    }

    private String normalizar(String texto) {
        return Normalizer.normalize(texto == null ? "" : texto.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").trim();
    }

    public record ResultadoImportacion(List<RutaMicro> rutas,
                                       List<String> hojasOmitidas,
                                       List<String> errores) {
        public ResultadoImportacion {
            rutas = List.copyOf(rutas);
            hojasOmitidas = List.copyOf(hojasOmitidas);
            errores = List.copyOf(errores);
        }
    }
}
