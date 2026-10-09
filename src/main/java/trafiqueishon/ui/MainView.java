package trafiqueishon.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.util.LinkedHashMap;
import java.util.Map;

public class MainView {

    private final BorderPane root;
    private final MapaCanvas mapa;

    // Labels de la status bar
    private Label lblCoords;
    private Label lblZoom;
    private Label lblVisibles;

    public MainView() {
        root = new BorderPane();
        root.getStyleClass().add("root-view");

        mapa = new MapaCanvas();
        root.setCenter(mapa);
        root.setRight(crearPanelControl());
        root.setBottom(crearStatusBar());

        // Conectar callback del mouse
        mapa.setOnMouseMoveCallback(lonlat -> {
            if (lblCoords != null) {
                lblCoords.setText(String.format("Lon: %.5f  Lat: %.5f", lonlat[0], lonlat[1]));
            }
        });
    }

    private TabPane crearPanelControl() {
        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("panel-control");
        tabs.setPrefWidth(340);
        tabs.setMinWidth(300);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        tabs.getTabs().addAll(
                new Tab("Mapa",     crearTabMapa()),
                new Tab("Rutas",    crearTabPlaceholder("Rutas",
                        "Gestion de rutas urbanas (proximamente)")),
                new Tab("Demanda",  crearTabPlaceholder("Demanda",
                        "Generacion de viajes y pasajeros (proximamente)")),
                new Tab("Metricas", crearTabPlaceholder("Metricas",
                        "Estadisticas en vivo (proximamente)")),
                new Tab("Escenario",crearTabPlaceholder("Escenario",
                        "Configuracion del escenario (proximamente)"))
        );
        return tabs;
    }

    // ==================== TAB MAPA ====================
    private VBox crearTabMapa() {
        VBox cont = new VBox(12);
        cont.setPadding(new Insets(15));
        cont.getStyleClass().add("tab-contenido");

        // --- Zoom ---
        Label lblZoom = new Label("Zoom");
        lblZoom.getStyleClass().add("tab-titulo");

        Button btnZoomIn  = new Button("+");
        Button btnZoomOut = new Button("\u2212");
        Button btnReset   = new Button("Reset");
        btnZoomIn.setPrefWidth(50);
        btnZoomOut.setPrefWidth(50);
        btnReset.setPrefWidth(80);

        btnZoomIn.setOnAction(e -> { mapa.zoomIn(); actualizarStatus(); });
        btnZoomOut.setOnAction(e -> { mapa.zoomOut(); actualizarStatus(); });
        btnReset.setOnAction(e -> { mapa.resetVistaPublico(); actualizarStatus(); });

        HBox filaZoom = new HBox(8, btnZoomOut, btnZoomIn, btnReset);
        filaZoom.setAlignment(Pos.CENTER_LEFT);

        // --- Centrar ---
        Label lblCentrar = new Label("Centrar en");
        lblCentrar.getStyleClass().add("tab-titulo");

        Button btnPlaza       = new Button("Plaza de Armas");
        Button btnTerminal    = new Button("Terminal");
        Button btnUniversidad = new Button("Universidad");
        Button btnMercado     = new Button("Mercado");
        btnPlaza.setPrefWidth(200);
        btnTerminal.setPrefWidth(200);
        btnUniversidad.setPrefWidth(200);
        btnMercado.setPrefWidth(200);

        btnPlaza.setOnAction(e       -> { mapa.centrarEn(-70.133, -15.499, 4.0); actualizarStatus(); });
        btnTerminal.setOnAction(e    -> { mapa.centrarEn(-70.140, -15.490, 4.0); actualizarStatus(); });
        btnUniversidad.setOnAction(e -> { mapa.centrarEn(-70.128, -15.502, 4.0); actualizarStatus(); });
        btnMercado.setOnAction(e     -> { mapa.centrarEn(-70.135, -15.497, 4.0); actualizarStatus(); });

        VBox filaCentrar = new VBox(6, btnPlaza, btnTerminal, btnUniversidad, btnMercado);

        // --- Filtros ---
        Label lblFiltros = new Label("Filtros por tipo");
        lblFiltros.getStyleClass().add("tab-titulo");

        Map<String, CheckBox> checks = new LinkedHashMap<>();
        String[] tiposBase = {"trunk", "primary", "secondary", "tertiary",
                "residential", "service", "footway", "path", "track"};

        VBox filaFiltros = new VBox(4);
        for (String tipo : tiposBase) {
            CheckBox cb = new CheckBox(tipo);
            cb.setSelected(true);
            cb.getStyleClass().add("checkbox-tipo");
            cb.selectedProperty().addListener((o, a, b) -> {
                mapa.setTipoVisible(tipo, b);
                actualizarStatus();
            });
            checks.put(tipo, cb);
            filaFiltros.getChildren().add(cb);
        }

        // --- Leyenda ---
        Label lblLeyenda = new Label("Leyenda");
        lblLeyenda.getStyleClass().add("tab-titulo");

        VBox leyenda = new VBox(4);
        leyenda.getChildren().addAll(
                crearItemLeyenda("#E892A2", "Trunk"),
                crearItemLeyenda("#FCD6A4", "Primary"),
                crearItemLeyenda("#F7FABD", "Secondary"),
                crearItemLeyenda("#FFFFFF", "Tertiary"),
                crearItemLeyenda("#AAAAAA", "Residential")
        );

        cont.getChildren().addAll(
                lblZoom, filaZoom,
                new Separator(),
                lblCentrar, filaCentrar,
                new Separator(),
                lblFiltros, filaFiltros,
                new Separator(),
                lblLeyenda, leyenda
        );

        ScrollPane scroll = new ScrollPane(cont);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("scroll-panel");

        VBox wrapper = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return wrapper;
    }

    private HBox crearItemLeyenda(String colorHex, String texto) {
        Rectangle rect = new Rectangle(14, 14);
        rect.setFill(Color.web(colorHex));
        rect.setStroke(Color.web("#555555"));
        rect.setStrokeWidth(0.5);

        Label lbl = new Label(texto);
        lbl.getStyleClass().add("leyenda-texto");

        HBox fila = new HBox(8, rect, lbl);
        fila.setAlignment(Pos.CENTER_LEFT);
        return fila;
    }

    private VBox crearTabPlaceholder(String titulo, String desc) {
        VBox cont = new VBox(10);
        cont.setPadding(new Insets(15));
        cont.getStyleClass().add("tab-contenido");

        Label t = new Label(titulo);
        t.getStyleClass().add("tab-titulo");

        Label d = new Label(desc);
        d.getStyleClass().add("tab-descripcion");
        d.setWrapText(true);

        cont.getChildren().addAll(t, d);
        return cont;
    }

    // ==================== STATUS BAR ====================
    private HBox crearStatusBar() {
        HBox bar = new HBox(20);
        bar.setPadding(new Insets(6, 15, 6, 15));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");

        lblCoords   = new Label("Lon: --  Lat: --");
        lblZoom     = new Label("Zoom: --");
        lblVisibles = new Label("Visibles: --");

        lblCoords.getStyleClass().add("status-item");
        lblZoom.getStyleClass().add("status-item");
        lblVisibles.getStyleClass().add("status-item");

        bar.getChildren().addAll(
                lblCoords,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                lblZoom,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                lblVisibles,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                item("FPS", "60"),
                item("Vehiculos", "0"),
                item("Buses", "0"),
                item("Pasajeros", "0"),
                item("Hora", "06:00"),
                item("Estado", "Listo")
        );
        HBox.setHgrow(bar, Priority.ALWAYS);
        return bar;
    }

    private Label item(String k, String v) {
        Label l = new Label(k + ": " + v);
        l.getStyleClass().add("status-item");
        return l;
    }

    private void actualizarStatus() {
        if (lblZoom != null) {
            lblZoom.setText(String.format("Zoom: %.2f", mapa.getZoom()));
        }
        if (lblVisibles != null) {
            lblVisibles.setText("Visibles: " + mapa.getViasVisibles()
                    + " / " + mapa.getTotalVias());
        }
    }

    public BorderPane getRoot() { return root; }
}