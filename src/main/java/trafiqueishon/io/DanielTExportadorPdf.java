package trafiqueishon.io;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.util.Matrix;
import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGNodo;
import trafiqueishon.ruta.LuisYPaletaColores;
import trafiqueishon.ruta.LuisYPuntoImportante;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Exporta rutas a PDF (horizontal, tamaño carta) con el estilo de croquis: fondo
 * blanco, calles grises finas, zonas urbanas punteadas, nombre de zona vertical,
 * leyenda, panel derecho y marco verde con línea amarilla.
 *
 * <p>Tres modos:</p>
 * <ul>
 *   <li>{@link #exportarVistaMultiple}: una sola lámina con todas las rutas
 *       marcadas, cada una con su color propio (ida continua, vuelta discontinua)
 *       y un panel con la lista de rutas.</li>
 *   <li>{@link #exportarVistaIndividual}: una lámina con la ida (azul) y la
 *       vuelta (roja) de una micro y su lista de paraderos.</li>
 *   <li>{@link #exportar}: una lámina individual por cada micro de la lista
 *       (el par "· Ida" / "· Vuelta" de un mismo servicio comparte página).</li>
 * </ul>
 */
public final class DanielTExportadorPdf {

    /** Zona rotulada en el croquis (posición del centro del nombre). */
    public record Zona(String nombre, double lon, double lat) {}

    private static final Color ROJO = new Color(222, 38, 38);
    private static final Color COLOR_IDA = Color.decode(LuisYPaletaColores.COLOR_RUTA_IDA);
    private static final Color COLOR_VUELTA = Color.decode(LuisYPaletaColores.COLOR_RUTA_VUELTA);
    private static final Color AZUL_TITULO = new Color(28, 62, 190);
    private static final Color AZUL_SEPARADOR = new Color(0, 140, 255);
    private static final Color VERDE = new Color(46, 158, 68);
    private static final Color AMARILLO = new Color(255, 214, 0);
    private static final Color CALLE = new Color(150, 150, 150);
    private static final Color PUNTEADO = new Color(185, 185, 185);
    private static final Color NEGRO = Color.BLACK;

    private static final float MARGEN_PAGINA = 12;
    private static final float GROSOR_VERDE = 9;
    private static final float GROSOR_AMARILLO = 1.4f;
    private static final float PROPORCION_PANEL = 0.25f;
    private static final float DESFASE_RUTAS = 1.5f;
    private static final float CELDA_URBANA = 9;

    private List<Zona> zonas;
    private String nombreCiudad = "JULIACA";

    public void setZonas(List<Zona> zonas) {
        this.zonas = zonas == null ? null : List.copyOf(zonas);
    }

    /** Nombre que se rotula en vertical cuando no hay zonas definidas. */
    public void setNombreCiudad(String nombreCiudad) {
        if (nombreCiudad != null && !nombreCiudad.isBlank()) this.nombreCiudad = nombreCiudad;
    }

    /** Una lámina individual por micro (ida azul / vuelta roja). */
    public void exportar(List<DanielTRutaExportable> rutas, Path destino) throws IOException {
        if (rutas.isEmpty()) throw new IllegalArgumentException("Selecciona al menos una ruta.");
        List<Lamina> laminas = agrupar(rutas);
        try (PDDocument documento = new PDDocument()) {
            for (Lamina lamina : laminas) dibujarLamina(documento, lamina);
            documento.save(destino.toFile());
        }
    }

    /**
     * Vista individual: la ida y la vuelta de una sola micro en una lámina.
     * Cualquiera de los dos sentidos puede ser {@code null}, pero no ambos.
     */
    public void exportarVistaIndividual(DanielTRutaExportable ida, DanielTRutaExportable vuelta, Path destino)
            throws IOException {
        if (ida == null && vuelta == null) {
            throw new IllegalArgumentException("Selecciona una micro con ida o vuelta.");
        }
        String titulo = nombreBase((ida != null ? ida : vuelta).nombre());
        try (PDDocument documento = new PDDocument()) {
            dibujarLamina(documento, new Lamina(titulo, ida, vuelta));
            documento.save(destino.toFile());
        }
    }

    /**
     * Vista múltiple: todas las rutas en una sola lámina, cada una con su color.
     * Las rutas de retorno ("· Vuelta") se trazan con línea discontinua.
     */
    public void exportarVistaMultiple(List<DanielTRutaExportable> rutas, String titulo, Path destino)
            throws IOException {
        if (rutas.isEmpty()) throw new IllegalArgumentException("Selecciona al menos una ruta.");
        try (PDDocument documento = new PDDocument()) {
            dibujarLaminaMultiple(documento, rutas,
                    titulo == null || titulo.isBlank() ? "Rutas seleccionadas" : titulo);
            documento.save(destino.toFile());
        }
    }

    // ------------------------------------------------------------------ agrupación

    private record Lamina(String titulo, DanielTRutaExportable partida, DanielTRutaExportable retorno) {}

    private static final class Acumulador {
        private final String titulo;
        private DanielTRutaExportable partida;
        private DanielTRutaExportable retorno;

        private Acumulador(String titulo) { this.titulo = titulo; }
    }

    private List<Lamina> agrupar(List<DanielTRutaExportable> rutas) {
        Map<String, Acumulador> porClave = new LinkedHashMap<>();
        for (DanielTRutaExportable ruta : rutas) {
            boolean vuelta = esRetorno(ruta);
            String titulo = nombreBase(ruta.nombre());
            String clave = titulo.toLowerCase(Locale.ROOT);
            int repeticion = 0;
            while (true) {
                Acumulador acumulador = porClave.computeIfAbsent(clave + "#" + repeticion,
                        k -> new Acumulador(titulo));
                DanielTRutaExportable ocupada = vuelta ? acumulador.retorno : acumulador.partida;
                if (ocupada == null) {
                    if (vuelta) acumulador.retorno = ruta;
                    else acumulador.partida = ruta;
                    break;
                }
                repeticion++;
            }
        }
        List<Lamina> laminas = new ArrayList<>();
        for (Acumulador a : porClave.values()) {
            laminas.add(new Lamina(a.titulo, a.partida, a.retorno));
        }
        return laminas;
    }

    private static boolean esRetorno(DanielTRutaExportable ruta) {
        String sentido = ruta.trayecto().sentido();
        if ("RETORNO".equalsIgnoreCase(sentido)) return true;
        return ruta.nombre().matches("(?i).*\\b(vuelta|retorno)\\s*$");
    }

    private static String nombreBase(String nombre) {
        String base = nombre.replaceAll("(?i)\\s*[·\\-–:|]?\\s*(ida|vuelta|partida|retorno)\\s*$", "")
                .trim();
        return base.isBlank() ? nombre : base;
    }

    // ------------------------------------------------------------------ lámina

    /** Trazo a dibujar sobre el croquis. */
    private record Capa(List<PieroGNodo[]> tramos, Color color, float desfase, boolean discontinua) {}

    /** Entrada de la leyenda del mapa. */
    private record EntradaLeyenda(Color color, String texto, boolean discontinua) {}

    /** Marco común de una lámina: devuelve {x0, y0, x1, y1, panelX, mapaX1}. */
    private float[] prepararHoja(PDDocument documento, PDPage hoja) {
        documento.addPage(hoja);
        float ancho = hoja.getMediaBox().getWidth();
        float alto = hoja.getMediaBox().getHeight();
        float interior = MARGEN_PAGINA + GROSOR_VERDE + 6;
        float x0 = interior, y0 = interior, x1 = ancho - interior, y1 = alto - interior;
        float panelX = x1 - (x1 - x0) * PROPORCION_PANEL;
        return new float[]{x0, y0, x1, y1, panelX, panelX - 9};
    }

    private static PDPage hojaHorizontal() {
        return new PDPage(new PDRectangle(PDRectangle.LETTER.getHeight(),
                PDRectangle.LETTER.getWidth()));
    }

    private void dibujarLamina(PDDocument documento, Lamina lamina) throws IOException {
        PDPage hoja = hojaHorizontal();
        float[] m = prepararHoja(documento, hoja);
        List<PieroGNodo[]> ida = trazo(lamina.partida());
        List<PieroGNodo[]> vuelta = trazo(lamina.retorno());
        float desfase = !ida.isEmpty() && !vuelta.isEmpty() ? DESFASE_RUTAS : 0;
        // La vuelta va debajo: así la ida (azul) queda visible donde comparten calle.
        List<Capa> capas = List.of(
                new Capa(vuelta, COLOR_VUELTA, desfase, false),
                new Capa(ida, COLOR_IDA, desfase, false));
        List<EntradaLeyenda> leyenda = List.of(
                new EntradaLeyenda(COLOR_IDA, "PARTIDA (IDA)", false),
                new EntradaLeyenda(COLOR_VUELTA, "RETORNO (VUELTA)", false));
        try (PDPageContentStream cs = new PDPageContentStream(documento, hoja)) {
            dibujarMapa(cs, lamina.titulo(), capas, List.of(ida.isEmpty() ? vuelta : ida),
                    true, leyenda, m[0], m[1], m[5], m[3]);
            dibujarSeparador(cs, m[4] - 7, m[1], m[3]);
            dibujarPanel(cs, lamina, m[4], m[1], m[2], m[3]);
            dibujarMarco(cs, hoja.getMediaBox().getWidth(), hoja.getMediaBox().getHeight());
        }
    }

    private void dibujarLaminaMultiple(PDDocument documento, List<DanielTRutaExportable> rutas,
                                       String titulo) throws IOException {
        PDPage hoja = hojaHorizontal();
        float[] m = prepararHoja(documento, hoja);
        List<Capa> capas = new ArrayList<>();
        List<List<PieroGNodo[]>> extremos = new ArrayList<>();
        // Vueltas primero (discontinuas, debajo) y luego idas (continuas, encima).
        for (boolean pasoIdas : new boolean[]{false, true}) {
            for (DanielTRutaExportable ruta : rutas) {
                boolean retorno = esRetorno(ruta);
                if (retorno == pasoIdas) continue;
                List<PieroGNodo[]> tramos = trazo(ruta);
                capas.add(new Capa(tramos, Color.decode(normalizarColor(ruta.color())),
                        retorno ? DESFASE_RUTAS : 0, retorno));
                if (!retorno) extremos.add(tramos);
            }
        }
        List<EntradaLeyenda> leyenda = List.of(
                new EntradaLeyenda(NEGRO, "IDA: LINEA CONTINUA", false),
                new EntradaLeyenda(NEGRO, "VUELTA: DISCONTINUA", true));
        try (PDPageContentStream cs = new PDPageContentStream(documento, hoja)) {
            dibujarMapa(cs, titulo, capas, extremos, false, leyenda, m[0], m[1], m[5], m[3]);
            dibujarSeparador(cs, m[4] - 7, m[1], m[3]);
            dibujarPanelMultiple(cs, rutas, m[4], m[1], m[2], m[3]);
            dibujarMarco(cs, hoja.getMediaBox().getWidth(), hoja.getMediaBox().getHeight());
        }
    }

    private static String normalizarColor(String color) {
        String c = color.trim();
        if (c.matches("#[0-9a-fA-F]{6}")) return c;
        if (c.matches("#[0-9a-fA-F]{8}")) return c.substring(0, 7);
        if (c.matches("0x[0-9a-fA-F]{6,8}")) return "#" + c.substring(2, 8);
        return "#444444";
    }

    private void dibujarMarco(PDPageContentStream cs, float ancho, float alto) throws IOException {
        float m = MARGEN_PAGINA + GROSOR_VERDE / 2;
        cs.setStrokingColor(VERDE);
        cs.setLineWidth(GROSOR_VERDE);
        cs.addRect(m, m, ancho - 2 * m, alto - 2 * m);
        cs.stroke();
        float a = MARGEN_PAGINA + GROSOR_VERDE + 1.5f;
        cs.setStrokingColor(AMARILLO);
        cs.setLineWidth(GROSOR_AMARILLO);
        cs.addRect(a, a, ancho - 2 * a, alto - 2 * a);
        cs.stroke();
    }

    private void dibujarSeparador(PDPageContentStream cs, float x, float y0, float y1)
            throws IOException {
        cs.setStrokingColor(AZUL_SEPARADOR);
        cs.setLineWidth(5);
        cs.moveTo(x, y0);
        cs.lineTo(x, y1);
        cs.stroke();
    }

    // ------------------------------------------------------------------ panel de paradas

    private void dibujarPanel(PDPageContentStream cs, Lamina lamina, float x0, float y0, float x1,
                              float y1) throws IOException {
        List<String> partida = paradas(lamina.partida());
        List<String> retorno = paradas(lamina.retorno());
        float margen = 10;
        float anchoTexto = x1 - x0 - margen * 2 - 12;
        float alturaUtil = y1 - y0 - margen * 2;
        int lineas = partida.size() + retorno.size();
        float tamano = Math.max(5.5f, Math.min(9f, (alturaUtil - 56) / (Math.max(1, lineas) * 1.45f)));
        float paso = tamano * 1.45f;

        float y = y1 - margen - 11;
        y = titulo(cs, "RECORRIDO DE PARTIDA", COLOR_IDA, x0 + margen, y);
        y = lista(cs, partida, x0 + margen, y, anchoTexto, tamano, paso, y0 + margen);
        y -= 14;
        y = titulo(cs, "RECORRIDO DE RETORNO", COLOR_VUELTA, x0 + margen, y);
        lista(cs, retorno, x0 + margen, y, anchoTexto, tamano, paso, y0 + margen);
    }

    /** Panel de la vista múltiple: una fila por micro con su color y distancia. */
    private void dibujarPanelMultiple(PDPageContentStream cs, List<DanielTRutaExportable> rutas,
                                      float x0, float y0, float x1, float y1) throws IOException {
        Map<String, double[]> kmPorMicro = new LinkedHashMap<>();
        Map<String, Color> colorPorMicro = new LinkedHashMap<>();
        for (DanielTRutaExportable ruta : rutas) {
            String base = nombreBase(ruta.nombre());
            double[] km = kmPorMicro.computeIfAbsent(base, k -> new double[2]);
            km[esRetorno(ruta) ? 1 : 0] += ruta.trayecto().distanciaMetros() / 1000.0;
            colorPorMicro.putIfAbsent(base, Color.decode(normalizarColor(ruta.color())));
        }
        float margen = 10;
        float anchoTexto = x1 - x0 - margen * 2 - 26;
        float alturaUtil = y1 - y0 - margen * 2 - 30;
        int filas = kmPorMicro.size();
        float tamano = Math.max(5.5f, Math.min(9f, alturaUtil / (Math.max(1, filas) * 2.6f)));
        float paso = tamano * 2.6f;
        float y = y1 - margen - 11;
        y = titulo(cs, "RUTAS INCLUIDAS (" + filas + ")", ROJO, x0 + margen, y);
        for (Map.Entry<String, double[]> fila : kmPorMicro.entrySet()) {
            if (y < y0 + margen) {
                cs.setNonStrokingColor(NEGRO);
                texto(cs, PDType1Font.HELVETICA_BOLD, tamano, "...", x0 + margen, y);
                break;
            }
            Color color = colorPorMicro.get(fila.getKey());
            cs.setStrokingColor(color);
            cs.setLineWidth(3f);
            cs.setLineCapStyle(1);
            cs.moveTo(x0 + margen, y + tamano * 0.35f);
            cs.lineTo(x0 + margen + 18, y + tamano * 0.35f);
            cs.stroke();
            cs.setNonStrokingColor(NEGRO);
            texto(cs, PDType1Font.HELVETICA_BOLD, tamano,
                    ajustar(PDType1Font.HELVETICA_BOLD, tamano, fila.getKey(), anchoTexto),
                    x0 + margen + 24, y);
            double[] km = fila.getValue();
            String detalle = String.format(Locale.ROOT, "IDA %.1f KM  /  VUELTA %.1f KM", km[0], km[1]);
            texto(cs, PDType1Font.HELVETICA, tamano * 0.85f, detalle, x0 + margen + 24,
                    y - tamano * 1.15f);
            y -= paso;
        }
    }

    private float titulo(PDPageContentStream cs, String texto, Color color, float x, float y)
            throws IOException {
        cs.setNonStrokingColor(color);
        texto(cs, PDType1Font.HELVETICA_BOLD, 11, texto, x, y);
        return y - 17;
    }

    private float lista(PDPageContentStream cs, List<String> nombres, float x, float y, float ancho,
                        float tamano, float paso, float limiteInferior) throws IOException {
        for (String nombre : nombres) {
            if (y < limiteInferior) {
                cs.setNonStrokingColor(NEGRO);
                texto(cs, PDType1Font.HELVETICA_BOLD, tamano, "...", x + 10, y);
                return y - paso;
            }
            cs.setNonStrokingColor(NEGRO);
            circulo(cs, x + 3, y + tamano * 0.33f, 2.1f);
            cs.fill();
            texto(cs, PDType1Font.HELVETICA_BOLD, tamano,
                    ajustar(PDType1Font.HELVETICA_BOLD, tamano, nombre, ancho), x + 11, y);
            y -= paso;
        }
        return y;
    }

    private List<String> paradas(DanielTRutaExportable ruta) {
        List<String> nombres = new ArrayList<>();
        if (ruta == null) return nombres;
        for (LuisYPuntoImportante punto : ruta.trayecto().puntos()) nombres.add(ascii(punto.nombre()));
        if (nombres.isEmpty()) {
            String anterior = null;
            for (PieroGEdge e : ruta.trayecto().edges()) {
                if (e.name == null || e.name.isBlank() || e.name.equals(anterior)) continue;
                nombres.add(ascii(e.name));
                anterior = e.name;
            }
        }
        return nombres;
    }

    // ------------------------------------------------------------------ mapa

    /**
     * Croquis común: encuadra todas las capas, dibuja calles de fondo en gris,
     * zonas punteadas y los trazos encima.
     *
     * @param extremos       trazos cuyo inicio/final se marca
     * @param rotularExtremos {@code true} escribe "INICIO"/"FINAL" (vista individual)
     */
    private void dibujarMapa(PDPageContentStream cs, String titulo, List<Capa> capas,
                             List<List<PieroGNodo[]>> extremos, boolean rotularExtremos,
                             List<EntradaLeyenda> leyenda, float mx0, float my0,
                             float mx1, float my1) throws IOException {
        double minLon = Double.POSITIVE_INFINITY, minLat = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
        double sumaLat = 0;
        long n = 0;
        List<PieroGNodo> todos = new ArrayList<>();
        Set<PieroGNodo> vistos = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Capa capa : capas) {
            for (PieroGNodo[] par : capa.tramos()) {
                for (PieroGNodo nodo : par) {
                    if (!vistos.add(nodo)) continue;
                    todos.add(nodo);
                    minLon = Math.min(minLon, nodo.lon);
                    maxLon = Math.max(maxLon, nodo.lon);
                    minLat = Math.min(minLat, nodo.lat);
                    maxLat = Math.max(maxLat, nodo.lat);
                    sumaLat += nodo.lat;
                    n++;
                }
            }
        }
        cs.setNonStrokingColor(Color.WHITE);
        cs.addRect(mx0, my0, mx1 - mx0, my1 - my0);
        cs.fill();
        encabezado(cs, titulo, mx0 + 10, my1 - 18);
        if (n == 0) {
            cs.setNonStrokingColor(NEGRO);
            texto(cs, PDType1Font.HELVETICA, 10, "No hay tramos viales para dibujar.", mx0 + 10, my1 - 44);
            dibujarLeyenda(cs, leyenda, posicionLeyenda(List.of(), mx0, my0, mx1, my1));
            return;
        }

        double factorLon = Math.max(0.01, Math.cos(Math.toRadians(sumaLat / n)));
        double anchoGeo = Math.max(1e-9, (maxLon - minLon) * factorLon);
        double altoGeo = Math.max(1e-9, maxLat - minLat);
        double pad = 26;
        double espacioX = (mx1 - mx0) - 2 * pad;
        double espacioY = (my1 - my0) - 2 * pad - 14;
        double escala = Math.min(espacioX / anchoGeo, espacioY / altoGeo);
        double inicioX = mx0 + pad + (espacioX - anchoGeo * escala) / 2;
        double inicioY = my0 + pad + (espacioY - altoGeo * escala) / 2;
        Proy proy = new Proy(minLon, minLat, factorLon, escala, inicioX, inicioY);

        cs.saveGraphicsState();
        cs.addRect(mx0, my0, mx1 - mx0, my1 - my0);
        cs.clip();
        List<PieroGEdge> calles = callesVisibles(todos, proy, mx0, my0, mx1, my1);
        double[] centro = dibujarZonasUrbanas(cs, calles, proy, mx0, my0, mx1, my1);
        dibujarCalles(cs, calles, proy);
        dibujarNombresZona(cs, proy, centro, mx0, my0, mx1, my1);
        for (Capa capa : capas) {
            dibujarRecorrido(cs, capa.tramos(), proy, capa.color(), capa.desfase(), capa.discontinua());
        }
        for (List<PieroGNodo[]> tramos : extremos) {
            Color color = rotularExtremos ? ROJO : colorDeCapa(capas, tramos);
            dibujarExtremos(cs, tramos, proy, color, rotularExtremos);
        }
        cs.restoreGraphicsState();

        List<float[]> puntosRuta = new ArrayList<>();
        for (Capa capa : capas) {
            for (PieroGNodo[] par : capa.tramos()) {
                float ax = proy.x(par[0]), ay = proy.y(par[0]), bx = proy.x(par[1]), by = proy.y(par[1]);
                puntosRuta.add(new float[]{ax, ay});
                puntosRuta.add(new float[]{(ax + bx) / 2, (ay + by) / 2});
                puntosRuta.add(new float[]{bx, by});
            }
        }
        dibujarLeyenda(cs, leyenda, posicionLeyenda(puntosRuta, mx0, my0, mx1, my1));
    }

    private static Color colorDeCapa(List<Capa> capas, List<PieroGNodo[]> tramos) {
        for (Capa capa : capas) if (capa.tramos() == tramos) return capa.color();
        return ROJO;
    }

    /** Pares origen-destino de los tramos de una ruta, en orden. */
    private List<PieroGNodo[]> trazo(DanielTRutaExportable ruta) {
        List<PieroGNodo[]> pares = new ArrayList<>();
        if (ruta == null) return pares;
        for (PieroGEdge e : ruta.trayecto().edges()) pares.add(new PieroGNodo[]{e.origen(), e.destino()});
        return pares;
    }

    private record Proy(double minLon, double minLat, double factorLon, double escala,
                        double inicioX, double inicioY) {
        float x(PieroGNodo n) { return (float) (inicioX + (n.lon - minLon) * factorLon * escala); }

        float y(PieroGNodo n) { return (float) (inicioY + (n.lat - minLat) * escala); }
    }

    /** Calles de la red alcanzables desde la ruta que caen dentro de la lámina. */
    private List<PieroGEdge> callesVisibles(List<PieroGNodo> semillas, Proy p, float mx0, float my0, float mx1,
                                      float my1) {
        float holgura = 30;
        Set<PieroGNodo> visitados = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<PieroGEdge> aristas = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<PieroGNodo> cola = new ArrayDeque<>();
        for (PieroGNodo s : semillas) if (visitados.add(s)) cola.add(s);
        while (!cola.isEmpty()) {
            PieroGNodo nodo = cola.poll();
            for (PieroGEdge e : nodo.salientes) {
                aristas.add(e);
                encolar(e.destino(), p, mx0 - holgura, my0 - holgura, mx1 + holgura, my1 + holgura,
                        visitados, cola);
            }
            for (PieroGEdge e : nodo.entrantes) {
                aristas.add(e);
                encolar(e.origen(), p, mx0 - holgura, my0 - holgura, mx1 + holgura, my1 + holgura,
                        visitados, cola);
            }
        }
        List<PieroGEdge> unicas = new ArrayList<>();
        for (PieroGEdge e : aristas) {
            if (e.origen().id > e.destino().id && tieneInversa(e)) continue;
            unicas.add(e);
        }
        return unicas;
    }

    private void encolar(PieroGNodo vecino, Proy p, float x0, float y0, float x1, float y1,
                         Set<PieroGNodo> visitados, Deque<PieroGNodo> cola) {
        float vx = p.x(vecino), vy = p.y(vecino);
        if (vx < x0 || vx > x1 || vy < y0 || vy > y1) return;
        if (visitados.add(vecino)) cola.add(vecino);
    }

    private boolean tieneInversa(PieroGEdge e) {
        for (PieroGEdge otra : e.destino().salientes) if (otra.destino() == e.origen()) return true;
        return false;
    }

    private void dibujarCalles(PDPageContentStream cs, List<PieroGEdge> calles, Proy p) throws IOException {
        cs.setStrokingColor(CALLE);
        cs.setLineWidth(0.35f);
        for (PieroGEdge e : calles) {
            cs.moveTo(p.x(e.origen()), p.y(e.origen()));
            cs.lineTo(p.x(e.destino()), p.y(e.destino()));
        }
        cs.stroke();
    }

    /** Puntea las celdas con mucha calle; devuelve el centro de masa (x, y) o null. */
    private double[] dibujarZonasUrbanas(PDPageContentStream cs, List<PieroGEdge> calles, Proy p,
                                         float mx0, float my0, float mx1, float my1)
            throws IOException {
        int cols = Math.max(1, (int) Math.ceil((mx1 - mx0) / CELDA_URBANA));
        int filas = Math.max(1, (int) Math.ceil((my1 - my0) / CELDA_URBANA));
        double[] largo = new double[cols * filas];
        for (PieroGEdge e : calles) {
            double ax = p.x(e.origen()), ay = p.y(e.origen());
            double bx = p.x(e.destino()), by = p.y(e.destino());
            int c = (int) Math.floor(((ax + bx) / 2 - mx0) / CELDA_URBANA);
            int f = (int) Math.floor(((ay + by) / 2 - my0) / CELDA_URBANA);
            if (c < 0 || c >= cols || f < 0 || f >= filas) continue;
            largo[f * cols + c] += Math.hypot(ax - bx, ay - by);
        }
        double[] suma = new double[cols * filas];
        double maximo = 0;
        for (int f = 0; f < filas; f++) {
            for (int c = 0; c < cols; c++) {
                double s = 0;
                for (int df = -1; df <= 1; df++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        int ff = f + df, cc = c + dc;
                        if (ff >= 0 && ff < filas && cc >= 0 && cc < cols) s += largo[ff * cols + cc];
                    }
                }
                suma[f * cols + c] = s;
                maximo = Math.max(maximo, s);
            }
        }
        if (maximo <= 0) return null;
        double umbral = maximo * 0.12;
        double sx = 0, sy = 0, peso = 0;
        cs.setNonStrokingColor(PUNTEADO);
        float paso = 3.6f;
        for (int f = 0; f < filas; f++) {
            for (int c = 0; c < cols; c++) {
                if (suma[f * cols + c] < umbral) continue;
                float cx0 = mx0 + c * CELDA_URBANA, cy0 = my0 + f * CELDA_URBANA;
                for (float dy = paso / 2; dy < CELDA_URBANA; dy += paso) {
                    for (float dx = paso / 2; dx < CELDA_URBANA; dx += paso) {
                        cs.addRect(cx0 + dx - 0.4f, cy0 + dy - 0.4f, 0.8f, 0.8f);
                    }
                }
                double w = largo[f * cols + c];
                sx += (cx0 + CELDA_URBANA / 2) * w;
                sy += (cy0 + CELDA_URBANA / 2) * w;
                peso += w;
            }
        }
        cs.fill();
        return peso > 0 ? new double[]{sx / peso, sy / peso} : null;
    }

    private void dibujarNombresZona(PDPageContentStream cs, Proy p, double[] centro, float mx0,
                                    float my0, float mx1, float my1) throws IOException {
        List<double[]> posiciones = new ArrayList<>();
        List<String> nombres = new ArrayList<>();
        if (zonas != null) {
            for (Zona z : zonas) {
                double x = p.inicioX() + (z.lon() - p.minLon()) * p.factorLon() * p.escala();
                double y = p.inicioY() + (z.lat() - p.minLat()) * p.escala();
                posiciones.add(new double[]{x, y});
                nombres.add(z.nombre());
            }
        } else if (centro != null) {
            posiciones.add(centro);
            nombres.add(nombreCiudad);
        }
        for (int i = 0; i < nombres.size(); i++) {
            double[] pos = posiciones.get(i);
            if (pos[0] < mx0 || pos[0] > mx1 || pos[1] < my0 || pos[1] > my1) continue;
            String texto = ascii(nombres.get(i)).toUpperCase(Locale.ROOT);
            float tam = 15;
            float ancho = PDType1Font.HELVETICA_BOLD.getStringWidth(texto) / 1000 * tam;
            cs.setNonStrokingColor(ROJO);
            cs.beginText();
            cs.setFont(PDType1Font.HELVETICA_BOLD, tam);
            cs.setTextMatrix(Matrix.getRotateInstance(Math.PI / 2, (float) pos[0] + tam * 0.35f,
                    (float) pos[1] - ancho / 2));
            cs.showText(texto);
            cs.endText();
        }
    }

    private void dibujarRecorrido(PDPageContentStream cs, List<PieroGNodo[]> tramos, Proy p, Color color,
                                  float desfase, boolean discontinua) throws IOException {
        if (tramos.isEmpty()) return;
        if (discontinua) cs.setLineDashPattern(new float[]{5f, 3f}, 0);
        List<float[]> puntos = new ArrayList<>();
        PieroGNodo ultimo = null;
        for (PieroGNodo[] par : tramos) {
            if (ultimo != par[0]) {
                dibujarPolilinea(cs, puntos, color, desfase);
                puntos.clear();
                puntos.add(new float[]{p.x(par[0]), p.y(par[0])});
            }
            puntos.add(new float[]{p.x(par[1]), p.y(par[1])});
            ultimo = par[1];
        }
        dibujarPolilinea(cs, puntos, color, desfase);
        if (discontinua) cs.setLineDashPattern(new float[0], 0);
    }

    private void dibujarPolilinea(PDPageContentStream cs, List<float[]> pts, Color color,
                                  float desfase) throws IOException {
        if (pts.size() < 2) return;
        cs.setStrokingColor(color);
        cs.setLineWidth(2.2f);
        cs.setLineCapStyle(1);
        cs.setLineJoinStyle(1);
        for (int i = 0; i < pts.size(); i++) {
            float[] a = pts.get(Math.max(0, i - 1));
            float[] b = pts.get(Math.min(pts.size() - 1, i + 1));
            float dx = b[0] - a[0], dy = b[1] - a[1];
            float longitud = (float) Math.hypot(dx, dy);
            float nx = longitud == 0 ? 0 : -dy / longitud * desfase;
            float ny = longitud == 0 ? 0 : dx / longitud * desfase;
            float x = pts.get(i)[0] + nx, y = pts.get(i)[1] + ny;
            if (i == 0) cs.moveTo(x, y);
            else cs.lineTo(x, y);
        }
        cs.stroke();
    }

    private void dibujarExtremos(PDPageContentStream cs, List<PieroGNodo[]> tramos, Proy p, Color color,
                                 boolean rotular) throws IOException {
        if (tramos.isEmpty()) return;
        PieroGNodo inicio = tramos.getFirst()[0];
        PieroGNodo fin = tramos.getLast()[1];
        extremo(cs, p.x(inicio), p.y(inicio), rotular ? "INICIO" : null, color);
        extremo(cs, p.x(fin), p.y(fin), rotular ? "FINAL" : null, color);
    }

    private void extremo(PDPageContentStream cs, float x, float y, String etiqueta, Color color)
            throws IOException {
        cs.setNonStrokingColor(Color.WHITE);
        circulo(cs, x, y, 4);
        cs.fill();
        cs.setStrokingColor(color);
        cs.setLineWidth(1.6f);
        circulo(cs, x, y, 4);
        cs.stroke();
        if (etiqueta == null) return;
        float tam = 6.5f;
        float ancho = PDType1Font.HELVETICA_BOLD.getStringWidth(etiqueta) / 1000 * tam;
        cs.setNonStrokingColor(Color.WHITE);
        cs.addRect(x + 6, y + 4, ancho + 3, tam + 2);
        cs.fill();
        cs.setNonStrokingColor(ROJO);
        texto(cs, PDType1Font.HELVETICA_BOLD, tam, etiqueta, x + 7.5f, y + 5.5f);
    }

    private void encabezado(PDPageContentStream cs, String titulo, float x, float y)
            throws IOException {
        String texto = ascii(titulo).toUpperCase(Locale.ROOT);
        float ancho = PDType1Font.HELVETICA_BOLD.getStringWidth(texto) / 1000 * 13;
        cs.setNonStrokingColor(Color.WHITE);
        cs.addRect(x - 4, y - 5, ancho + 8, 20);
        cs.fill();
        cs.setNonStrokingColor(AZUL_TITULO);
        texto(cs, PDType1Font.HELVETICA_BOLD, 13, texto, x, y);
    }

    // ------------------------------------------------------------------ leyenda

    /** Coloca la leyenda en la primera esquina (abajo-derecha, abajo-izquierda, arriba...) sin ruta. */
    private float[] posicionLeyenda(List<float[]> ruta, float mx0, float my0, float mx1, float my1) {
        float ancho = 188, alto = 64, m = 8;
        float[][] candidatas = {
                {mx1 - ancho - m, my0 + m}, {mx0 + m, my0 + m},
                {mx1 - ancho - m, my1 - alto - m - 22}, {mx0 + m, my1 - alto - m - 22}};
        for (float[] c : candidatas) {
            boolean libre = true;
            for (float[] p : ruta) {
                if (p[0] >= c[0] - 6 && p[0] <= c[0] + ancho + 6
                        && p[1] >= c[1] - 6 && p[1] <= c[1] + alto + 6) {
                    libre = false;
                    break;
                }
            }
            if (libre) return c;
        }
        return candidatas[0];
    }

    private void dibujarLeyenda(PDPageContentStream cs, List<EntradaLeyenda> entradas, float[] pos)
            throws IOException {
        float ancho = 188, alto = 64;
        float x = pos[0], y = pos[1];
        cs.setNonStrokingColor(Color.WHITE);
        cs.addRect(x, y, ancho, alto);
        cs.fill();
        cs.setStrokingColor(NEGRO);
        cs.setLineWidth(0.8f);
        cs.addRect(x, y, ancho, alto);
        cs.stroke();

        globo(cs, x + 22, y + alto / 2, 17);
        cs.setNonStrokingColor(NEGRO);
        texto(cs, PDType1Font.HELVETICA_BOLD, 7.5f, "LEYENDA", x + 46, y + alto - 14);
        for (int i = 0; i < Math.min(2, entradas.size()); i++) {
            EntradaLeyenda e = entradas.get(i);
            entradaLeyenda(cs, e.color(), e.texto(), e.discontinua(), x + 46, y + alto - 29 - i * 14);
        }
        cs.setNonStrokingColor(NEGRO);
        circulo(cs, x + 49, y + alto - 55.7f, 1.8f);
        cs.fill();
        cs.setStrokingColor(ROJO);
        cs.setLineWidth(1.2f);
        circulo(cs, x + 65.5f, y + alto - 55.7f, 3);
        cs.stroke();
        texto(cs, PDType1Font.HELVETICA_BOLD, 6.5f, "INICIO / FINAL", x + 79, y + alto - 58);
    }

    private void entradaLeyenda(PDPageContentStream cs, Color color, String texto,
                                boolean discontinua, float x, float y) throws IOException {
        cs.setNonStrokingColor(NEGRO);
        circulo(cs, x + 3, y + 2.3f, 1.8f);
        cs.fill();
        cs.setStrokingColor(color);
        cs.setLineWidth(2.4f);
        if (discontinua) cs.setLineDashPattern(new float[]{4f, 2.5f}, 0);
        cs.moveTo(x + 9, y + 2.3f);
        cs.lineTo(x + 28, y + 2.3f);
        cs.stroke();
        if (discontinua) cs.setLineDashPattern(new float[0], 0);
        cs.setNonStrokingColor(NEGRO);
        texto(cs, PDType1Font.HELVETICA_BOLD, 6.5f, texto, x + 33, y);
    }

    /** Globo terráqueo en blanco y negro. */
    private void globo(PDPageContentStream cs, float cx, float cy, float r) throws IOException {
        cs.setNonStrokingColor(Color.WHITE);
        circulo(cs, cx, cy, r);
        cs.fill();
        cs.setStrokingColor(NEGRO);
        cs.setLineWidth(0.9f);
        circulo(cs, cx, cy, r);
        cs.stroke();
        elipse(cs, cx, cy, r * 0.45f, r);
        cs.stroke();
        cs.moveTo(cx, cy - r);
        cs.lineTo(cx, cy + r);
        cs.moveTo(cx - r, cy);
        cs.lineTo(cx + r, cy);
        float m = (float) Math.sqrt(r * r - (r * 0.5f) * (r * 0.5f));
        cs.moveTo(cx - m, cy + r * 0.5f);
        cs.lineTo(cx + m, cy + r * 0.5f);
        cs.moveTo(cx - m, cy - r * 0.5f);
        cs.lineTo(cx + m, cy - r * 0.5f);
        cs.stroke();
        cs.setNonStrokingColor(NEGRO);
        cs.moveTo(cx - r * 0.6f, cy + r * 0.5f);
        cs.lineTo(cx - r * 0.2f, cy + r * 0.6f);
        cs.lineTo(cx - r * 0.1f, cy + r * 0.1f);
        cs.lineTo(cx - r * 0.5f, cy - r * 0.1f);
        cs.closePath();
        cs.fill();
        cs.moveTo(cx + r * 0.15f, cy - r * 0.1f);
        cs.lineTo(cx + r * 0.55f, cy - r * 0.2f);
        cs.lineTo(cx + r * 0.4f, cy - r * 0.6f);
        cs.lineTo(cx + r * 0.2f, cy - r * 0.5f);
        cs.closePath();
        cs.fill();
    }

    // ------------------------------------------------------------------ primitivas

    private void circulo(PDPageContentStream cs, float cx, float cy, float r) throws IOException {
        elipse(cs, cx, cy, r, r);
    }

    private void elipse(PDPageContentStream cs, float cx, float cy, float rx, float ry)
            throws IOException {
        float k = 0.5522848f;
        cs.moveTo(cx + rx, cy);
        cs.curveTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry);
        cs.curveTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy);
        cs.curveTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry);
        cs.curveTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy);
        cs.closePath();
    }

    private void texto(PDPageContentStream cs, PDType1Font fuente, float tamano, String texto,
                       float x, float y) throws IOException {
        cs.beginText();
        cs.setFont(fuente, tamano);
        cs.newLineAtOffset(x, y);
        cs.showText(ascii(texto));
        cs.endText();
    }

    private String ajustar(PDType1Font fuente, float tamano, String texto, float maximo)
            throws IOException {
        String actual = ascii(texto).toUpperCase(Locale.ROOT);
        while (actual.length() > 4 && fuente.getStringWidth(actual) / 1000 * tamano > maximo) {
            actual = actual.substring(0, actual.length() - 4) + "...";
        }
        return actual;
    }

    private String ascii(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^\\x20-\\x7E]", "?");
    }
}
