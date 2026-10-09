package trafiqueishon.ui;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import trafiqueishon.geo.GeoJsonParser;
import trafiqueishon.geo.Proyector;
import trafiqueishon.geo.Via;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private final Canvas canvas;
    private final GeoJsonParser parser = new GeoJsonParser();
    private final Proyector proyector = new Proyector();

    private List<Via> vias;
    private boolean cargado = false;

    // Estado de vista
    private double offsetX = 0, offsetY = 0;
    private double zoom = 1.0;
    private double escalaBase = 1.0;
    private int nivelZoom = 2;   // indice en ZOOMS

    private static final double[] ZOOMS = {
            0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0,
            12.0, 16.0, 24.0, 32.0, 48.0, 64.0
    };

    private static final double MARGEN = 0.05;
    private static final double ZOOM_RESIDENCIALES = 1.5;

    private double lastMouseX, lastMouseY;
    private boolean necesitaRedibujar = true;

    public MapaCanvas() {
        canvas = new Canvas();
        getChildren().add(canvas);

        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());

        widthProperty().addListener((o, a, b) -> {
            recalcularEscalaBase();
            necesitaRedibujar = true;
        });
        heightProperty().addListener((o, a, b) -> {
            recalcularEscalaBase();
            necesitaRedibujar = true;
        });

        // Zoom por pasos discretos
        setOnScroll(e -> {
            if (!cargado) return;
            double delta = e.getDeltaY();
            if (Math.abs(delta) < 0.5) return;

            int nuevoNivel = nivelZoom + (delta > 0 ? 1 : -1);
            if (nuevoNivel < 0) nuevoNivel = 0;
            if (nuevoNivel >= ZOOMS.length) nuevoNivel = ZOOMS.length - 1;
            if (nuevoNivel == nivelZoom) return;

            double nuevoZoom = ZOOMS[nuevoNivel];
            double cx = e.getX();
            double cy = e.getY();

            offsetX = cx - (cx - offsetX) * (nuevoZoom / zoom);
            offsetY = cy - (cy - offsetY) * (nuevoZoom / zoom);
            zoom = nuevoZoom;
            nivelZoom = nuevoNivel;

            necesitaRedibujar = true;
            redibujar();
        });

        // Pan
        setOnMousePressed(e -> { lastMouseX = e.getX(); lastMouseY = e.getY(); });
        setOnMouseDragged(e -> {
            if (!cargado) return;
            offsetX += e.getX() - lastMouseX;
            offsetY += e.getY() - lastMouseY;
            lastMouseX = e.getX();
            lastMouseY = e.getY();
            necesitaRedibujar = true;
            redibujar();
        });

        // Doble clic: reset
        setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && cargado) resetVista();
        });

        cargarMapa();
    }

    private void cargarMapa() {
        new Thread(() -> {
            try {
                List<Via> cargadas = parser.parsearDesdeRecurso("/mapas/juliaca.geojson");
                proyector.preProyectar(cargadas);

                Platform.runLater(() -> {
                    vias = cargadas;
                    cargado = true;
                    recalcularEscalaBase();
                    resetVista();
                    System.out.println("[MapaCanvas] Vias cargadas: " + vias.size());
                });
            } catch (Exception ex) {
                System.err.println("[MapaCanvas] Error: " + ex.getMessage());
                ex.printStackTrace();
            }
        }, "cargador-geojson").start();
    }

    private void recalcularEscalaBase() {
        if (canvas.getWidth() <= 1 || canvas.getHeight() <= 1) return;
        double anchoMapa = canvas.getWidth() * (1 - 2 * MARGEN);
        double altoMapa  = canvas.getHeight() * (1 - 2 * MARGEN);
        escalaBase = Math.min(anchoMapa, altoMapa);
    }

    private void resetVista() {
        if (!cargado) return;
        recalcularEscalaBase();
        nivelZoom = 3;
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
        if (!cargado) return;

        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();

        gc.setFill(Color.web("#1E1E2E"));
        gc.fillRect(0, 0, w, h);

        // Viewport en coordenadas normalizadas
        double escalaActual = escalaBase * zoom;
        double vpMinX = (0 - offsetX) / escalaActual;
        double vpMaxX = (w - offsetX) / escalaActual;
        double vpMinY = (0 - offsetY) / escalaActual;
        double vpMaxY = (h - offsetY) / escalaActual;

        double m = 0.02;
        vpMinX -= m; vpMaxX += m;
        vpMinY -= m; vpMaxY += m;

        // 1. Principales siempre
        dibujarGrupo(gc, false, vpMinX, vpMaxX, vpMinY, vpMaxY);

        // 2. Residenciales solo si zoom >= 1.5
        if (zoom >= ZOOM_RESIDENCIALES) {
            dibujarGrupo(gc, true, vpMinX, vpMaxX, vpMinY, vpMaxY);
        }

        // Info arriba
        gc.setFill(Color.web("#89B4FA"));
        gc.setFont(Font.font("System", 14));
        gc.fillText("Juliaca - " + vias.size() + " vias | zoom: "
                + String.format("%.2f", zoom)
                + " | doble clic para reset", 15, 25);
    }

    /**
     * Agrupa vias por color y hace UN stroke por grupo.
     */
    private void dibujarGrupo(GraphicsContext gc, boolean secundarias,
                              double vpMinX, double vpMaxX,
                              double vpMinY, double vpMaxY) {

        Map<Color, List<Via>> porColor = new HashMap<>();

        for (Via v : vias) {
            if (v.highway == null) continue;
            if (v.projX == null || v.projX.length < 2) continue;
            if (secundarias != esSecundaria(v.highway)) continue;

            // Culling
            if (v.maxX < vpMinX || v.minX > vpMaxX) continue;
            if (v.maxY < vpMinY || v.minY > vpMaxY) continue;

            Color c = colorPorTipo(v.highway);
            porColor.computeIfAbsent(c, k -> new ArrayList<>()).add(v);
        }

        // Un stroke por color
        for (Map.Entry<Color, List<Via>> e : porColor.entrySet()) {
            gc.setStroke(e.getKey());
            gc.setLineWidth(secundarias ? 1.0 : 2.0);
            gc.setLineCap(StrokeLineCap.ROUND);
            gc.setLineJoin(StrokeLineJoin.ROUND);
            gc.beginPath();
            for (Via v : e.getValue()) {
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
                || h.equals("unclassified");
    }

    private Color colorPorTipo(String h) {
        if (h == null) return Color.web("#444444");
        switch (h) {
            case "trunk":
            case "trunk_link":     return Color.web("#E892A2");
            case "primary":
            case "primary_link":   return Color.web("#FCD6A4");
            case "secondary":
            case "secondary_link": return Color.web("#F7FABD");
            case "tertiary":
            case "tertiary_link":  return Color.web("#FFFFFF");
            case "residential":    return Color.web("#AAAAAA");
            case "service":        return Color.web("#666666");
            case "footway":
            case "path":
            case "steps":          return Color.web("#555555");
            case "track":          return Color.web("#8B7355");
            default:               return Color.web("#777777");
        }
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        if (necesitaRedibujar && cargado) {
            necesitaRedibujar = false;
            redibujar();
        }
    }
}