package trafiqueishon.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import trafiqueishon.ruta.LuisYPaletaColores;

/**
 * Leyenda del mapa: un botón en la esquina inferior derecha que despliega, al pulsarlo, todos los
 * elementos visuales agrupados por categoría. Los colores son los mismos que usa el canvas.
 */
public final class LuisYLeyendaFlotante extends VBox {

    private static final double ANCHO_MUESTRA = 30;
    private final VBox contenido;
    private final Button boton = new Button("🎨 Leyenda ▲");

    public LuisYLeyendaFlotante() {
        setAlignment(Pos.BOTTOM_RIGHT);
        setSpacing(6);
        setPickOnBounds(false);
        contenido = crearContenido();
        ScrollPane scroll = new ScrollPane(contenido);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportWidth(290);
        scroll.setMaxHeight(480);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox panel = new VBox(scroll);
        panel.getStyleClass().add("panel-estadisticas");
        panel.setVisible(false);
        panel.setManaged(false);
        boton.getStyleClass().add("boton-compacto");
        boton.setOnAction(e -> {
            boolean ver = !panel.isVisible();
            panel.setVisible(ver);
            panel.setManaged(ver);
            boton.setText(ver ? "🎨 Leyenda ▼" : "🎨 Leyenda ▲");
        });
        getChildren().addAll(panel, boton);
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
    }

    private VBox crearContenido() {
        VBox caja = new VBox(5);
        caja.setPadding(new Insets(10));
        caja.getChildren().addAll(
                categoria("Pasajeros"),
                fila(punto(Color.STEELBLUE), "Esperando en la parada"),
                fila(punto(Color.MEDIUMPURPLE), "Transbordo (espera o camina a su 2.º micro)"),
                fila(punto(Color.ORANGE), "Caminando hacia la parada"),
                categoria("Rutas"),
                fila(linea(Color.web(LuisYPaletaColores.COLOR_RUTA_IDA), false), "Ida (línea sólida)"),
                fila(linea(Color.web(LuisYPaletaColores.COLOR_RUTA_VUELTA), true), "Vuelta (línea punteada)"),
                categoria("Vehículos"),
                fila(vehiculo(Color.web(LuisYPaletaColores.COLOR_RUTA_ACTIVA)), "En marcha: color de su ruta"),
                fila(vehiculo(Color.GRAY), "Detenido en semáforo o parada"),
                fila(vehiculo(Color.CRIMSON), "Micro con sobrecupo"),
                categoria("Semáforos"),
                fila(punto(Color.LIMEGREEN), "Verde: paso"),
                fila(punto(Color.RED), "Rojo: alto"),
                fila(punto(Color.GOLD), "Amarillo: precaución"),
                categoria("Eventos"),
                fila(triangulo(Color.RED), "Accidente"),
                fila(humo(), "Trancadera: humo gris sobre la vía"),
                fila(punto(Color.ORANGE), "Marcador de trancadera / semáforo roto"),
                fila(lluvia(), "Lluvia: zona azul con gotas diagonales"),
                fila(punto(Color.GOLD), "Gasolinera"),
                fila(punto(Color.RED.deriveColor(0, 1, 1, 0.45)), "Vía cerrada / nodo bloqueado"),
                categoria("Otros"),
                fila(policia(), "Policía de tránsito"),
                fila(zonaSinCobertura(), "Zona sin cobertura (demanda sin parada a ≤ 300 m)"));
        return caja;
    }

    private static Label categoria(String texto) {
        Label l = new Label(texto);
        l.setFont(Font.font("System", FontWeight.BOLD, 13));
        l.setPadding(new Insets(6, 0, 0, 0));
        return l;
    }

    private static HBox fila(javafx.scene.Node muestra, String texto) {
        StackPane caja = new StackPane(muestra);
        caja.setMinWidth(ANCHO_MUESTRA);
        caja.setPrefWidth(ANCHO_MUESTRA);
        Label etiqueta = new Label(texto);
        etiqueta.setWrapText(true);
        etiqueta.setFont(Font.font("System", 12));
        HBox fila = new HBox(10, caja, etiqueta);
        fila.setAlignment(Pos.CENTER_LEFT);
        return fila;
    }

    private static Shape punto(Color color) {
        Circle c = new Circle(6, color);
        c.setStroke(Color.web("#10243F", 0.6));
        return c;
    }

    private static Shape linea(Color color, boolean punteada) {
        Line l = new Line(0, 0, ANCHO_MUESTRA - 4, 0);
        l.setStroke(color);
        l.setStrokeWidth(4);
        if (punteada) l.getStrokeDashArray().addAll(6.0, 5.0);
        return l;
    }

    private static Shape vehiculo(Color color) {
        Rectangle r = new Rectangle(18, 10, color);
        r.setArcWidth(4);
        r.setArcHeight(4);
        return r;
    }

    private static javafx.scene.Node humo() {
        javafx.scene.Group g = new javafx.scene.Group();
        double[][] bocanadas = {{6, 12, 3, 0.55}, {14, 8, 4.5, 0.4}, {23, 3, 6, 0.25}};
        for (double[] b : bocanadas) g.getChildren().add(new Circle(b[0], b[1], b[2], Color.GRAY.deriveColor(0, 1, 1, b[3])));
        return g;
    }

    private static javafx.scene.Node lluvia() {
        Circle zona = new Circle(11, Color.DODGERBLUE.deriveColor(0, 1, 1, 0.18));
        zona.setStroke(Color.DODGERBLUE.deriveColor(0, 1, 1, 0.8));
        javafx.scene.Group g = new javafx.scene.Group(zona);
        for (double x : new double[]{-5, 0, 5}) {
            Line gota = new Line(x + 2, -6, x - 2, 5);
            gota.setStroke(Color.web("#CFE8FF"));
            gota.setStrokeWidth(1.4);
            g.getChildren().add(gota);
        }
        return g;
    }

    private static Shape triangulo(Color color) {
        Polygon p = new Polygon(8, 0, 16, 14, 0, 14);
        p.setFill(color);
        p.setStroke(Color.WHITE);
        return p;
    }

    private static javafx.scene.Node policia() {
        Label p = new Label("P");
        p.setTextFill(Color.WHITE);
        p.setFont(Font.font("System", FontWeight.BOLD, 11));
        Circle c = new Circle(9, Color.DODGERBLUE);
        c.setStroke(Color.WHITE);
        return new StackPane(c, p);
    }

    private static Shape zonaSinCobertura() {
        Circle c = new Circle(10, Color.GRAY.deriveColor(0, 1, 1, 0.12));
        c.setStroke(Color.GRAY);
        c.setStrokeWidth(1.5);
        c.getStrokeDashArray().addAll(4.0, 3.0);
        return c;
    }
}
