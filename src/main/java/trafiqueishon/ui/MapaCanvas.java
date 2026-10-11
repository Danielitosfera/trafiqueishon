package trafiqueishon.ui;

import javafx.application.Platform;
import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;
import trafiqueishon.geo.GeoJsonParser;
import trafiqueishon.geo.Proyector;
import trafiqueishon.geo.Via;


import trafiqueishon.geo.Nodo;
import java.util.ArrayList;
import java.util.List;
import trafiqueishon.geo.GrafoVial;
import trafiqueishon.geo.RutaResult;
import trafiqueishon.geo.Edge;
import trafiqueishon.simulacion.MarcadorReferencia;
import trafiqueishon.simulacion.SimuladorTrafico;
import trafiqueishon.simulacion.SemaforoInteligente;
import trafiqueishon.simulacion.PersistenciaEscenario;
/**
 * Canvas del mapa OPTIMIZADO.
 *
 * - Las vias se pre-proyectan UNA VEZ a coordenadas 0..1.
 * - Zoom y pan se aplican con transformaciones simples.
 * - Culling: solo se dibujan vias dentro del viewport.
 * - Agrupacion por color: 1 sola llamada a stroke() por color.
 * - Culling por zoom: no dibuja residenciales si zoom < 1.5.
 * - Zoom por pasos discretos: sin anti-zoom, sin redibujados excesivos.
 */
public class MapaCanvas extends Pane {
    private static final long INTERVALO_RENDER_NANOS = 1_000_000_000L / 30;
    private static final Color FONDO = Color.web("#1E1E2E");
    private static final Color COLOR_TITULO = Color.web("#89B4FA");
    private static final Color COLOR_RUTA = Color.web("#F38BA8");
    private static final Color COLOR_ORIGEN = Color.web("#A6E3A1");
    private static final Color COLOR_DESTINO = Color.web("#F38BA8");
    private static final Color COLOR_MARCADOR_BORDE = Color.web("#1E1E2E");
    private static final Color COLOR_REFERENCIA = Color.web("#F9E2AF");
    private static final Color COLOR_REFERENCIA_TEXTO = Color.web("#CDD6F4");
    private static final Color COLOR_VIA_DEFAULT = Color.web("#777777");
    private static final Font FONT_TITULO = Font.font("System", 14);
    private static final Font FONT_MARCADOR = Font.font("System", 10);
    private static final Font FONT_REFERENCIA = Font.font("System", 11);
    private static final Color[] COLORES_CALOR = {
            Color.LIMEGREEN, Color.GOLD, Color.CRIMSON
    };
    private static final Color[] COLORES_VIAS_GRUPO = {
            Color.web("#E892A2"), Color.web("#FCD6A4"), Color.web("#F7FABD"),
            Color.WHITE, Color.web("#AAAAAA"), Color.web("#666666"),
            Color.web("#555555"), Color.web("#8B7355"), COLOR_VIA_DEFAULT
    };
    private static final Color[] COLORES_VEHICULO_FX = {
            Color.web("#00E5FF"), Color.web("#76FF03"), Color.web("#FF9100"),
            Color.web("#FF007F"), Color.web("#FF007F"), Color.web("#FFEA00")
    };

    private final Canvas canvas;
    private final GeoJsonParser parser = new GeoJsonParser();
    private final PersistenciaEscenario persistencia = new PersistenciaEscenario();
    private Proyector proyector = new Proyector();

    // Tipos de via ocultos (filtros)
    private final java.util.Set<String> tiposOcultos = new java.util.HashSet<>();
    private final java.util.Set<String> tiposDisponibles = new java.util.TreeSet<>();
    private List<Via> vias;
    private STRtree indiceVias;
    private STRtree indiceAristas;
    private boolean cargado = false;
    private final List<List<Via>> gruposVias = crearGruposVias();

    private GrafoVial grafo;        // <-- NUEVO

    // ===== RUTAS =====
    private RutaResult rutaActual;
    private Nodo marcadorOrigen;
    private Nodo marcadorDestino;
    private boolean modoSeleccion = false;
    private int faseSeleccion = 0; // 0 = origen, 1 = destino
    private java.util.function.Consumer<Integer> onSeleccionCallback;
    private Runnable onMapaCargado;
    private java.util.function.Consumer<String> onError;
    private SimuladorTrafico.FrameVehiculos frameVehiculos;
    private final List<MarcadorReferencia> referencias = new ArrayList<>();
    private boolean modoColocacionReferencia;
    private String nombreReferenciaPendiente;
    private java.util.function.Consumer<MarcadorReferencia> onReferenciaAgregada;
    private java.util.function.IntConsumer onQuitarSemaforo;
    private boolean modoQuitarSemaforo;
    private List<SemaforoInteligente> semaforos = List.of();
    private boolean heatmapActivo;
    private String nombreMapa = "Juliaca";
    private String empresaFiltro = "";


    // Estado de vista
    private double offsetX = 0, offsetY = 0;
    private double zoom = 1.0;
    private double escalaBase = 1.0;
    private int nivelZoom = 2;   // indice en ZOOMS
    private double anchoAnterior;
    private double altoAnterior;
    private double viewportMinX;
    private double viewportMaxX;
    private double viewportMinY;
    private double viewportMaxY;
    private String textoTitulo;
    private double zoomTextoTitulo = Double.NaN;

    private static final double[] ZOOMS = {
            0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0,
            12.0, 16.0, 24.0, 32.0, 48.0
    };
    private static final double MIN_ZOOM = ZOOMS[0];
    private static final double MAX_ZOOM = ZOOMS[ZOOMS.length - 1];
    private static final double MIN_VEHICLE_SIZE = 4.5;
    private static final double MAX_VEHICLE_SIZE = 8.0;

    private static final double MARGEN = 0.05;
    private static final double ZOOM_RESIDENCIALES = 1.5;

    private double lastMouseX, lastMouseY;
    private boolean necesitaRedibujar = true;
    private long ultimoRenderNanos;
    private final AnimationTimer renderizador = new AnimationTimer() {
        @Override
        public void handle(long ahora) {
            if (!necesitaRedibujar || ahora - ultimoRenderNanos < INTERVALO_RENDER_NANOS) return;
            necesitaRedibujar = false;
            ultimoRenderNanos = ahora;
            renderizar();
        }
    };

    public MapaCanvas() {
        canvas = new Canvas();
        getChildren().add(canvas);

        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());

        widthProperty().addListener((o, a, b) -> recalcularEscalaBaseConservandoCentro());
        heightProperty().addListener((o, a, b) -> recalcularEscalaBaseConservandoCentro());

        // Zoom por pasos discretos
        setOnScroll(e -> {
            if (!cargado) return;
            double delta = e.getDeltaY();
            if (Math.abs(delta) < 0.5) return;

            int nuevoNivel = nivelZoom + (delta > 0 ? 1 : -1);
            if (nuevoNivel < 0) nuevoNivel = 0;
            if (nuevoNivel >= ZOOMS.length) nuevoNivel = ZOOMS.length - 1;
            if (nuevoNivel == nivelZoom) return;

            aplicarNivelZoom(nuevoNivel, e.getX(), e.getY());
        });

        // Pan
        setOnMousePressed(e -> { lastMouseX = e.getX(); lastMouseY = e.getY(); });
        setOnMouseDragged(e -> {
            if (!cargado) return;
            offsetX += e.getX() - lastMouseX;
            offsetY += e.getY() - lastMouseY;
            lastMouseX = e.getX();
            lastMouseY = e.getY();
            redibujar();
        });

        // Clic: seleccion de origen/destino; doble clic: reset
        setOnMouseClicked(e -> {
            if (!cargado) return;
            if (e.getClickCount() == 2) { resetVista(); return; }

            if (modoQuitarSemaforo) {
                quitarSemaforoEn(e.getX(), e.getY());
                return;
            }

            if (modoColocacionReferencia) {
                double[] norm = pantallaANormalizado(e.getX(), e.getY());
                double[] lonlat = normalizadoALonLat(norm[0], norm[1]);
                MarcadorReferencia referencia =
                        new MarcadorReferencia(nombreReferenciaPendiente, lonlat[0], lonlat[1]);
                referencias.add(referencia);
                modoColocacionReferencia = false;
                if (onReferenciaAgregada != null) onReferenciaAgregada.accept(referencia);
                necesitaRedibujar = true;
                redibujar();
                return;
            }

            if (modoSeleccion && grafo != null) {
                double[] norm = pantallaANormalizado(e.getX(), e.getY());
                Nodo cercano = grafo.nodoMasCercano(norm[0], norm[1]);
                if (cercano == null) return;

                if (faseSeleccion == 0) {
                    marcadorOrigen = cercano;
                    faseSeleccion = 1;
                } else {
                    marcadorDestino = cercano;
                    faseSeleccion = 0;
                    modoSeleccion = false;
                }
                if (onSeleccionCallback != null) {
                    onSeleccionCallback.accept(faseSeleccion);
                }
                necesitaRedibujar = true;
                redibujar();
            }
        });

        renderizador.start();
        cargarMapa(null);
    }

    public void cargarMapa(java.nio.file.Path archivo) {
        new Thread(() -> {
            try {
                java.nio.file.Path rutaSeleccionada = archivo == null
                        ? persistencia.cargarGeoJsonSeleccionado() : archivo;
                List<Via> cargadas = rutaSeleccionada == null
                        ? parser.parsearDesdeRecurso("/mapas/juliaca.geojson")
                        : parser.parsearArchivo(rutaSeleccionada);
                Proyector proyectorNuevo = new Proyector();
                proyectorNuevo.preProyectar(cargadas);
                STRtree indice = new STRtree();
                for (Via via : cargadas) {
                    if (via.highway == null || via.projX == null || via.projX.length < 2) continue;
                    indice.insert(new Envelope(via.minX, via.maxX, via.minY, via.maxY), via);
                }
                indice.build();

                // === FASE 3: construir grafo ANTES de liberar coords ===
                GrafoVial g = new GrafoVial();
                g.construir(cargadas, proyectorNuevo);
                if (g.numNodos() < 2 || g.numEdges() == 0) {
                    throw new java.io.IOException(
                            "La red no contiene suficientes tramos viales vehiculares.");
                }
                System.out.println("[Grafo] " + g.resumen());
                STRtree indiceEdge = new STRtree();
                for (Edge edge : g.getEdges()) {
                    indiceEdge.insert(new Envelope(
                            Math.min(edge.origen.x, edge.destino.x),
                            Math.max(edge.origen.x, edge.destino.x),
                            Math.min(edge.origen.y, edge.destino.y),
                            Math.max(edge.origen.y, edge.destino.y)), edge);
                }
                indiceEdge.build();

                // Ahora sí, liberar coords para ahorrar memoria
                for (Via v : cargadas) v.liberarCoords();

                Platform.runLater(() -> {
                    vias = cargadas;
                    indiceVias = indice;
                    indiceAristas = indiceEdge;
                    grafo = g;
                    copiarProyector(proyectorNuevo);
                    nombreMapa = rutaSeleccionada == null
                            ? "Juliaca" : rutaSeleccionada.getFileName().toString();
                    textoTitulo = null;
                    if (archivo != null) {
                        try {
                            persistencia.guardarGeoJsonSeleccionado(archivo);
                        } catch (java.io.IOException e) {
                            if (onError != null) {
                                onError.accept("Mapa cargado; no se pudo guardar su seleccion: "
                                        + e.getMessage());
                            }
                        }
                    }
                    cargado = true;
                    recolectarTipos();
                    recalcularEscalaBase();
                    resetVista();
                    System.out.println("[MapaCanvas] Vias cargadas: " + vias.size());
                    System.out.println("[MapaCanvas] Tipos: " + tiposDisponibles);
                    if (onMapaCargado != null) onMapaCargado.run();
                });
            } catch (Exception ex) {
                System.err.println("[MapaCanvas] Error: " + ex.getMessage());
                ex.printStackTrace();
                Platform.runLater(() -> {
                    if (onError != null) onError.accept("No se pudo cargar la red vial: " + ex.getMessage());
                });
            }
        }, "cargador-geojson").start();

    }

    private void copiarProyector(Proyector origen) {
        proyector = origen;
    }

    private void recalcularEscalaBase() {
        if (canvas.getWidth() <= 1 || canvas.getHeight() <= 1) return;
        double anchoMapa = canvas.getWidth() * (1 - 2 * MARGEN);
        double altoMapa  = canvas.getHeight() * (1 - 2 * MARGEN);
        escalaBase = Math.min(anchoMapa, altoMapa);
        anchoAnterior = canvas.getWidth();
        altoAnterior = canvas.getHeight();
    }

    private void recalcularEscalaBaseConservandoCentro() {
        double ancho = canvas.getWidth();
        double alto = canvas.getHeight();
        if (ancho <= 1 || alto <= 1) return;
        if (anchoAnterior > 1 && altoAnterior > 1) {
            double escalaAnterior = escalaBase * zoom;
            double centroMapaX = (anchoAnterior / 2.0 - offsetX) / escalaAnterior;
            double centroMapaY = (altoAnterior / 2.0 - offsetY) / escalaAnterior;
            escalaBase = Math.min(ancho, alto) * (1 - 2 * MARGEN);
            offsetX = ancho / 2.0 - centroMapaX * escalaBase * zoom;
            offsetY = alto / 2.0 - centroMapaY * escalaBase * zoom;
        } else {
            recalcularEscalaBase();
        }
        anchoAnterior = ancho;
        altoAnterior = alto;
        redibujar();
    }

    private void resetVista() {
        if (!cargado) return;
        recalcularEscalaBase();
        nivelZoom = 2;
        zoom = ZOOMS[nivelZoom];
        double anchoMapa = escalaBase * zoom;
        double altoMapa  = escalaBase * zoom;
        offsetX = (canvas.getWidth() - anchoMapa) / 2.0;
        offsetY = (canvas.getHeight() - altoMapa) / 2.0;
        necesitaRedibujar = true;
        redibujar();
    }

    private double pantallaX(double nx) { return nx * escalaBase * zoom + offsetX; }
    private double pantallaY(double ny) { return ny * escalaBase * zoom + offsetY; }

    private void redibujar() {
        necesitaRedibujar = true;
    }

    private void renderizar() {
        if (!cargado) return;

        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();

        gc.setFill(FONDO);
        gc.fillRect(0, 0, w, h);

        // Viewport en coordenadas normalizadas
        double escalaActual = escalaBase * zoom;
        viewportMinX = (0 - offsetX) / escalaActual;
        viewportMaxX = (w - offsetX) / escalaActual;
        viewportMinY = (0 - offsetY) / escalaActual;
        viewportMaxY = (h - offsetY) / escalaActual;

        double m = 0.02;
        viewportMinX -= m; viewportMaxX += m;
        viewportMinY -= m; viewportMaxY += m;

        @SuppressWarnings("unchecked")
        List<Via> visibles = indiceVias == null ? List.of()
                : indiceVias.query(new Envelope(
                        viewportMinX, viewportMaxX, viewportMinY, viewportMaxY));

        // Las vias se consultan por viewport en el indice espacial, no se recorre toda la ciudad.
        dibujarGrupo(gc, visibles, false);
        if (zoom >= ZOOM_RESIDENCIALES) dibujarGrupo(gc, visibles, true);
        if (heatmapActivo) dibujarHeatmap(gc);

        // 3. Ruta y marcadores
        dibujarRuta(gc);
        dibujarReferencias(gc);
        dibujarSemaforos(gc);
        dibujarVehiculos(gc);

        // Info arriba
        if (textoTitulo == null || zoomTextoTitulo != zoom) {
            zoomTextoTitulo = zoom;
            textoTitulo = nombreMapa + " - " + vias.size() + " vias | zoom: "
                    + String.format("%.2f", zoom) + " | doble clic para reset";
        }
        gc.setFill(COLOR_TITULO);
        gc.setFont(FONT_TITULO);
        gc.fillText(textoTitulo, 15, 25);
    }

    private void dibujarHeatmap(GraphicsContext gc) {
        if (grafo == null) return;
        if (indiceAristas == null) return;
        @SuppressWarnings("unchecked")
        List<Edge> visibles = indiceAristas.query(new Envelope(
                viewportMinX, viewportMaxX, viewportMinY, viewportMaxY));
        for (int grupo = 0; grupo < COLORES_CALOR.length; grupo++) {
            gc.setStroke(COLORES_CALOR[grupo]);
            gc.setLineWidth(2.4);
            gc.beginPath();
            for (Edge edge : visibles) {
                int categoria = edge.factorTrafico < 1.3 ? 0
                        : edge.factorTrafico < 2.0 ? 1 : 2;
                if (categoria != grupo) continue;
                double x1 = pantallaX(edge.origen.x);
                double y1 = pantallaY(edge.origen.y);
                double x2 = pantallaX(edge.destino.x);
                double y2 = pantallaY(edge.destino.y);
                gc.moveTo(x1, y1);
                gc.lineTo(x2, y2);
            }
            gc.stroke();
        }
    }

    /**
     * Agrupa vias por color y hace UN stroke por grupo.
     */
    private void dibujarGrupo(GraphicsContext gc, List<Via> visibles, boolean secundarias) {
        for (List<Via> grupo : gruposVias) grupo.clear();

        for (Via v : visibles) {
            if (v.highway == null) continue;
            if (v.projX == null || v.projX.length < 2) continue;
            if (secundarias != esSecundaria(v.highway)) continue;

            // NUEVO: filtro por tipo oculto
            if (tiposOcultos.contains(v.highway)) continue;

            int indiceColor = indiceColorVia(v.highway);
            if (indiceColor >= 0) gruposVias.get(indiceColor).add(v);
        }

        for (int grupo = 0; grupo < gruposVias.size(); grupo++) {
            List<Via> viasGrupo = gruposVias.get(grupo);
            if (viasGrupo.isEmpty()) continue;
            gc.setStroke(COLORES_VIAS_GRUPO[grupo]);
            gc.setLineWidth(secundarias ? 1.0 : 2.0);
            gc.setLineCap(StrokeLineCap.ROUND);
            gc.setLineJoin(StrokeLineJoin.ROUND);
            gc.beginPath();
            for (Via v : viasGrupo) {
                int n = v.projX.length;
                gc.moveTo(pantallaX(v.projX[0]), pantallaY(v.projY[0]));
                for (int i = 1; i < n; i++) {
                    gc.lineTo(pantallaX(v.projX[i]), pantallaY(v.projY[i]));
                }
            }
            gc.stroke();
        }
    }

    private boolean esSecundaria(String h) {
        return h.equals("residential") || h.equals("service") || h.equals("footway")
                || h.equals("path") || h.equals("steps") || h.equals("track")
                || h.equals("unclassified") || h.equals("living_street")
                || h.equals("pedestrian") || h.equals("cycleway")
                || h.equals("construction") || h.equals("proposed");
    }

    private int indiceColorVia(String tipo) {
        if (tipo == null) return COLORES_VIAS_GRUPO.length - 1;
        return switch (tipo) {
            case "motorway", "motorway_link", "trunk", "trunk_link" -> 0;
            case "primary", "primary_link" -> 1;
            case "secondary", "secondary_link" -> 2;
            case "tertiary", "tertiary_link" -> 3;
            case "residential", "living_street" -> 4;
            case "service", "services" -> 5;
            case "footway", "path", "steps" -> 6;
            case "track" -> 7;
            default -> COLORES_VIAS_GRUPO.length - 1;
        };
    }

    private static List<List<Via>> crearGruposVias() {
        List<List<Via>> grupos = new ArrayList<>(COLORES_VIAS_GRUPO.length);
        for (int i = 0; i < COLORES_VIAS_GRUPO.length; i++) {
            grupos.add(new ArrayList<>());
        }
        return grupos;
    }

    private boolean estaEnViewport(double x, double y) {
        return x >= viewportMinX && x <= viewportMaxX
                && y >= viewportMinY && y <= viewportMaxY;
    }

    /** Dibuja la ruta actual en rojo y los marcadores origen/destino. */
    private void dibujarRuta(GraphicsContext gc) {
        if (rutaActual != null && !rutaActual.esVacia()) {
            gc.setStroke(COLOR_RUTA);
            gc.setLineWidth(4.0);
            gc.setLineCap(StrokeLineCap.ROUND);
            gc.setLineJoin(StrokeLineJoin.ROUND);
            gc.beginPath();
            List<Nodo> nodos = rutaActual.getNodos();
            for (int i = 1; i < nodos.size(); i++) {
                Nodo origen = nodos.get(i - 1);
                Nodo destino = nodos.get(i);
                if (Math.max(origen.x, destino.x) < viewportMinX
                        || Math.min(origen.x, destino.x) > viewportMaxX
                        || Math.max(origen.y, destino.y) < viewportMinY
                        || Math.min(origen.y, destino.y) > viewportMaxY) continue;
                gc.moveTo(pantallaX(origen.x), pantallaY(origen.y));
                gc.lineTo(pantallaX(destino.x), pantallaY(destino.y));
            }
            gc.stroke();
        }

        dibujarMarcador(gc, marcadorOrigen, COLOR_ORIGEN, "O");
        dibujarMarcador(gc, marcadorDestino, COLOR_DESTINO, "D");
    }

    private void dibujarMarcador(GraphicsContext gc, Nodo n, Color color, String letra) {
        if (n == null || !estaEnViewport(n.x, n.y)) return;
        double px = pantallaX(n.x);
        double py = pantallaY(n.y);
        gc.setFill(color);
        gc.fillOval(px - 7, py - 7, 14, 14);
        gc.setStroke(COLOR_MARCADOR_BORDE);
        gc.setLineWidth(2);
        gc.strokeOval(px - 7, py - 7, 14, 14);
        gc.setFill(COLOR_MARCADOR_BORDE);
        gc.setFont(FONT_MARCADOR);
        gc.fillText(letra, px - 3, py + 4);
    }

    private void dibujarVehiculos(GraphicsContext gc) {
        if (frameVehiculos == null || frameVehiculos.cantidad() == 0) return;
        double tamano = Math.clamp(
                MIN_VEHICLE_SIZE + Math.log(zoom / MIN_ZOOM) / Math.log(2) * 0.45,
                MIN_VEHICLE_SIZE, MAX_VEHICLE_SIZE);
        double mitad = tamano / 2;
        int empresaFiltrada = empresaFiltro.isBlank() ? -1
                : frameVehiculos.nombresEmpresas().indexOf(empresaFiltro);
        for (int tipo = 0; tipo < COLORES_VEHICULO_FX.length; tipo++) {
            gc.setFill(COLORES_VEHICULO_FX[tipo]);
            gc.beginPath();
            boolean contieneVehiculos = false;
            for (int i = 0; i < frameVehiculos.cantidad(); i++) {
                if (empresaFiltrada >= 0 && frameVehiculos.empresas()[i] != empresaFiltrada) continue;
                if (!empresaFiltro.isBlank() && empresaFiltrada < 0) continue;
                if (Byte.toUnsignedInt(frameVehiculos.tipos()[i]) != tipo) continue;
                double px = pantallaX(frameVehiculos.x()[i]);
                double py = pantallaY(frameVehiculos.y()[i]);
                if (px < -mitad || py < -mitad || px > canvas.getWidth() + mitad
                        || py > canvas.getHeight() + mitad) continue;
                gc.rect(px - mitad, py - mitad, tamano, tamano);
                contieneVehiculos = true;
            }
            if (contieneVehiculos) gc.fill();
        }
    }

    public void setEmpresaFiltro(String empresa) {
        ejecutarEnHiloFx(() -> {
            empresaFiltro = empresa == null || empresa.equals("Todas las empresas")
                    ? "" : empresa;
            redibujar();
        });
    }

    private void dibujarSemaforos(GraphicsContext gc) {
        for (SemaforoInteligente semaforo : semaforos) {
            if (!estaEnViewport(semaforo.nodo().x, semaforo.nodo().y)) continue;
            double px = pantallaX(semaforo.nodo().x);
            double py = pantallaY(semaforo.nodo().y);
            gc.setFill(colorSemaforo(semaforo.colorParaEje(true)));
            gc.fillOval(px - 5, py - 3, 6, 6);
            gc.setFill(colorSemaforo(semaforo.colorParaEje(false)));
            gc.fillOval(px, py - 3, 6, 6);
        }
    }

    private Color colorSemaforo(SemaforoInteligente.ColorLuz color) {
        return switch (color) {
            case VERDE -> COLORES_CALOR[0];
            case AMARILLO -> COLORES_CALOR[1];
            case ROJO -> COLORES_CALOR[2];
        };
    }

    private void dibujarReferencias(GraphicsContext gc) {
        gc.setFont(FONT_REFERENCIA);
        for (MarcadorReferencia referencia : referencias) {
            double nx = (referencia.lon() - proyector.getMinLon()) / proyector.getAnchoGeo();
            double ny = (proyector.getMaxLat() - referencia.lat()) / proyector.getAltoGeo();
            if (!estaEnViewport(nx, ny)) continue;
            double px = pantallaX(nx);
            double py = pantallaY(ny);
            gc.setFill(COLOR_REFERENCIA);
            gc.fillOval(px - 5, py - 5, 10, 10);
            gc.setFill(COLOR_REFERENCIA_TEXTO);
            gc.fillText(referencia.nombre(), px + 7, py - 5);
        }
    }

    // ==================== API DE RUTAS ====================

    public void setRuta(RutaResult ruta) {
        ejecutarEnHiloFx(() -> {
            rutaActual = ruta;
            necesitaRedibujar = true;
            if (cargado) redibujar();
        });
    }

    public void setFrameVehiculos(SimuladorTrafico.FrameVehiculos frame) {
        ejecutarEnHiloFx(() -> {
            frameVehiculos = frame;
            necesitaRedibujar = true;
            if (cargado) redibujar();
        });
    }

    public void setSemaforos(List<SemaforoInteligente> semaforos) {
        List<SemaforoInteligente> copia = List.copyOf(semaforos);
        ejecutarEnHiloFx(() -> {
            this.semaforos = copia;
            necesitaRedibujar = true;
        });
    }

    public void setOnQuitarSemaforo(java.util.function.IntConsumer callback) {
        onQuitarSemaforo = callback;
    }

    public void activarModoQuitarSemaforo(boolean activar) {
        modoQuitarSemaforo = activar;
    }

    public boolean isModoQuitarSemaforo() {
        return modoQuitarSemaforo;
    }

    private void quitarSemaforoEn(double px, double py) {
        SemaforoInteligente cercano = null;
        double distanciaMinima = 14 * 14;
        for (SemaforoInteligente semaforo : semaforos) {
            double dx = pantallaX(semaforo.nodo().x) - px;
            double dy = pantallaY(semaforo.nodo().y) - py;
            double distancia = dx * dx + dy * dy;
            if (distancia < distanciaMinima) {
                distanciaMinima = distancia;
                cercano = semaforo;
            }
        }
        if (cercano == null || onQuitarSemaforo == null) return;
        onQuitarSemaforo.accept(cercano.nodo().id);
        modoQuitarSemaforo = false;
    }

    public void setReferencias(List<MarcadorReferencia> referencias) {
        List<MarcadorReferencia> copia = List.copyOf(referencias);
        ejecutarEnHiloFx(() -> {
            this.referencias.clear();
            this.referencias.addAll(copia);
            necesitaRedibujar = true;
            if (cargado) redibujar();
        });
    }

    public void setHeatmapActivo(boolean activo) {
        ejecutarEnHiloFx(() -> {
            heatmapActivo = activo;
            necesitaRedibujar = true;
            if (cargado) redibujar();
        });
    }

    private void ejecutarEnHiloFx(Runnable accion) {
        if (Platform.isFxApplicationThread()) accion.run();
        else Platform.runLater(accion);
    }

    public String getNombreMapa() { return nombreMapa; }

    public void activarColocacionReferencia(String nombre) {
        if (nombre == null || nombre.isBlank()) throw new IllegalArgumentException("Escribe el nombre del punto.");
        nombreReferenciaPendiente = nombre.trim();
        modoColocacionReferencia = true;
        modoSeleccion = false;
    }

    public void setOnReferenciaAgregada(
            java.util.function.Consumer<MarcadorReferencia> callback) {
        onReferenciaAgregada = callback;
    }

    public List<MarcadorReferencia> getReferencias() {
        return List.copyOf(referencias);
    }

    public void setOnMapaCargado(Runnable callback) {
        onMapaCargado = callback;
        if (cargado && onMapaCargado != null) onMapaCargado.run();
    }

    public void setOnError(java.util.function.Consumer<String> callback) {
        onError = callback;
    }

    public void limpiarRuta() {
        this.rutaActual = null;
        this.marcadorOrigen = null;
        this.marcadorDestino = null;
        this.faseSeleccion = 0;
        necesitaRedibujar = true;
        if (cargado) redibujar();
    }

    public Nodo getMarcadorOrigen() { return marcadorOrigen; }
    public Nodo getMarcadorDestino() { return marcadorDestino; }

    /** Activa el modo clic: el primer clic fija origen, el segundo destino. */
    public void activarModoSeleccion(boolean activar) {
        this.modoSeleccion = activar;
        this.faseSeleccion = 0;
    }

    public boolean isModoSeleccion() { return modoSeleccion; }

    /** Se dispara cada vez que se fija origen o destino (fase 1 = origen puesto). */
    public void setOnSeleccion(java.util.function.Consumer<Integer> cb) {
        this.onSeleccionCallback = cb;
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        if (cargado) redibujar();
    }
    // ==================== CONTROLES PUBLICOS ====================

    /** Zoom in por pasos discretos. */
    public void zoomIn() {
        if (!cargado) return;
        int nuevoNivel = Math.min(nivelZoom + 1, ZOOMS.length - 1);
        if (nuevoNivel == nivelZoom) return;
        aplicarNivelZoom(nuevoNivel, canvas.getWidth() / 2.0, canvas.getHeight() / 2.0);
    }

    /** Zoom out por pasos discretos. */
    public void zoomOut() {
        if (!cargado) return;
        int nuevoNivel = Math.max(nivelZoom - 1, 0);
        if (nuevoNivel == nivelZoom) return;
        aplicarNivelZoom(nuevoNivel, canvas.getWidth() / 2.0, canvas.getHeight() / 2.0);
    }

    /** Reset completo de la vista. */
    public void resetVistaPublico() {
        resetVista();
    }

    /** Centra el mapa en coordenadas geograficas dadas. */
    public void centrarEn(double lon, double lat, double zoomDeseado) {
        if (!cargado) return;
        // Convertir lon/lat a coords normalizadas 0..1
        double nx = (lon - proyector.getMinLon()) / proyector.getAnchoGeo();
        double ny = (proyector.getMaxLat() - lat) / proyector.getAltoGeo();
        // Ajustar zoom al nivel mas cercano
        int mejorNivel = nivelZoom;
        double mejorDif = Double.MAX_VALUE;
        double zoomAcotado = Math.clamp(zoomDeseado, MIN_ZOOM, MAX_ZOOM);
        for (int i = 0; i < ZOOMS.length; i++) {
            double dif = Math.abs(ZOOMS[i] - zoomAcotado);
            if (dif < mejorDif) { mejorDif = dif; mejorNivel = i; }
        }
        zoom = ZOOMS[mejorNivel];
        nivelZoom = mejorNivel;

        double escalaActual = escalaBase * zoom;
        offsetX = canvas.getWidth()  / 2.0 - nx * escalaActual;
        offsetY = canvas.getHeight() / 2.0 - ny * escalaActual;

        necesitaRedibujar = true;
        redibujar();
    }

    /** Cambia visibilidad de un tipo de via. */
    public void setTipoVisible(String tipo, boolean visible) {
        ejecutarEnHiloFx(() -> {
            if (visible) tiposOcultos.remove(tipo);
            else tiposOcultos.add(tipo);
            necesitaRedibujar = true;
            redibujar();
        });
    }



    private void aplicarNivelZoom(int nuevoNivel, double cx, double cy) {
        double nuevoZoom = ZOOMS[nuevoNivel];
        offsetX = cx - (cx - offsetX) * (nuevoZoom / zoom);
        offsetY = cy - (cy - offsetY) * (nuevoZoom / zoom);
        zoom = nuevoZoom;
        nivelZoom = nuevoNivel;
        necesitaRedibujar = true;
        redibujar();
    }

    // ==================== ESTADO PARA STATUS BAR ====================

    /** Convierte coordenadas de pantalla a normalizadas 0..1. */
    public double[] pantallaANormalizado(double px, double py) {
        double escalaActual = escalaBase * zoom;
        double nx = (px - offsetX) / escalaActual;
        double ny = (py - offsetY) / escalaActual;
        return new double[]{nx, ny};
    }

    /** Convierte normalizado 0..1 a lon/lat usando el proyector. */
    public double[] normalizadoALonLat(double nx, double ny) {
        double lon = proyector.getMinLon() + nx * proyector.getAnchoGeo();
        double lat = proyector.getMaxLat() - ny * proyector.getAltoGeo();
        return new double[]{lon, lat};
    }

    /** Zoom actual. */
    public double getZoom() { return zoom; }

    /** Numero total de vias cargadas. */
    public int getTotalVias() { return vias == null ? 0 : vias.size(); }

    /**
     * Cuenta vias visibles en el viewport actual (aplicando filtros).
     */
    public int getViasVisibles() {
        if (vias == null) return 0;
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        double escalaActual = escalaBase * zoom;
        double vpMinX = (0 - offsetX) / escalaActual - 0.02;
        double vpMaxX = (w - offsetX) / escalaActual + 0.02;
        double vpMinY = (0 - offsetY) / escalaActual - 0.02;
        double vpMaxY = (h - offsetY) / escalaActual + 0.02;

        int count = 0;
        @SuppressWarnings("unchecked")
        List<Via> candidatas = indiceVias == null ? List.of()
                : indiceVias.query(new Envelope(vpMinX, vpMaxX, vpMinY, vpMaxY));
        for (Via v : candidatas) {
            if (v.highway == null || v.projX == null || v.projX.length < 2) continue;
            if (tiposOcultos.contains(v.highway)) continue;
            count++;
        }
        return count;
    }

    // ==================== CALLBACK MOUSE (status bar) ====================

    /**
     * Callback para que MainView actualice la status bar al mover el mouse.
     * Usa addEventFilter para NO pisar el setOnMouseDragged del pan.
     */
    public void setOnMouseMoveCallback(java.util.function.Consumer<double[]> cb) {
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, e -> {
            if (!cargado) return;
            double[] norm = pantallaANormalizado(e.getX(), e.getY());
            double[] lonlat = normalizadoALonLat(norm[0], norm[1]);
            cb.accept(lonlat);
        });
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, e -> {
            if (!cargado) return;
            double[] norm = pantallaANormalizado(e.getX(), e.getY());
            double[] lonlat = normalizadoALonLat(norm[0], norm[1]);
            cb.accept(lonlat);
        });
    }

// ==================== TIPOS DISPONIBLES ====================

    /** Llena tiposDisponibles; llamar tras cargar. */
    private void recolectarTipos() {
        tiposDisponibles.clear();
        if (vias == null) return;
        for (Via v : vias) {
            if (v.highway != null) tiposDisponibles.add(v.highway);
        }
    }

    /** Tipos que aparecen realmente en el mapa. */
    public java.util.Set<String> getTiposDisponibles() { return tiposDisponibles; }

    /** Grafo vial construido a partir de las vias. */
    public GrafoVial getGrafo() { return grafo; }

    public void cerrar() {
        renderizador.stop();
    }

}