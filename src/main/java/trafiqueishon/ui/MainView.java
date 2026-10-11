package trafiqueishon.ui;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import trafiqueishon.geo.Nodo;
import trafiqueishon.geo.Pathfinder;
import trafiqueishon.geo.RutaResult;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.FileChooser;
import java.util.List;
import trafiqueishon.simulacion.MarcadorReferencia;

public class MainView {

    private final BorderPane root;
    private final MapaCanvas mapa;
    private final SimulacionController simulacion;
    private VBox panelControl;
    private Button btnPanelControl;
    private ComboBox<String> selectorTema;
    private Stage ventanaMicros;

    // Labels de la status bar
    private Label lblCoords;
    private Label lblZoom;
    private Label lblVisibles;
    private Label lblVehiculos;
    private Label lblHora;
    private Label lblEstado;

    // Componentes de la pestana Rutas
    private Label lblResultadoRuta;
    private Button btnOrigen;
    private Button btnDestino;
    private ComboBox<String> cbModo;
    private CheckBox chkAlternativa;
    private Button btnCalcularRuta;
    private final ListView<String> listaReferencias = new ListView<>();
    private TextField nombreReferencia;

    public MainView() {
        root = new BorderPane();
        root.getStyleClass().add("root-view");

        mapa = new MapaCanvas();
        simulacion = new SimulacionController(mapa, (estadisticas, ejecutando) -> {
            if (lblVehiculos != null) {
                lblVehiculos.setText("Vehículos: " + estadisticas.vehiculosActivos());
                lblHora.setText("Hora: " + String.format("%02d:%02d",
                        (6 + (int) (estadisticas.tiempoSimuladoSegundos() / 3600)) % 24,
                        ((int) (estadisticas.tiempoSimuladoSegundos() / 60)) % 60));
                lblEstado.setText("Estado: " + (ejecutando ? "En curso"
                        : estadisticas.vehiculosActivos() == 0 ? "Listo" : "Pausado"));
                actualizarStatus();
            }
        });
        root.setCenter(mapa);
        root.setRight(crearPanelControl());
        root.setTop(crearToolbar());
        root.setBottom(crearStatusBar());
        root.sceneProperty().addListener((observable, anterior, escena) -> {
            if (escena != null && selectorTema != null) aplicarTema(selectorTema.getValue());
        });
        simulacion.setOnReferenciasActualizadas(this::actualizarListaReferencias);

        // Conectar callback del mouse
        mapa.setOnMouseMoveCallback(lonlat -> {
            if (lblCoords != null) {
                lblCoords.setText(String.format("Long.: %.5f  Lat.: %.5f", lonlat[0], lonlat[1]));
            }
        });
    }

    private VBox crearPanelControl() {
        panelControl = new VBox(8);
        panelControl.getStyleClass().add("panel-control");
        panelControl.setPrefWidth(530);
        panelControl.setMinWidth(510);
        Accordion secciones = new Accordion(
                new TitledPane("Mapa", crearTabMapa()),
                new TitledPane("Rutas", crearTabRutas()),
                new TitledPane("Puntos", crearTabReferencias()),
                new TitledPane("Tráfico", simulacion.crearTabDemanda()),
                new TitledPane("Métricas", simulacion.crearTabMetricas()),
                new TitledPane("Escenario", simulacion.crearTabEscenario()));
        secciones.setExpandedPane(secciones.getPanes().getFirst());
        VBox.setVgrow(secciones, Priority.ALWAYS);
        panelControl.getChildren().add(secciones);
        return panelControl;
    }

    private HBox crearToolbar() {
        HBox toolbar = new HBox(12);
        toolbar.setPadding(new Insets(10, 16, 10, 16));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("status-bar");
        Label titulo = new Label("TRAFIQUEISHON  /  SIMULADOR URBANO");
        titulo.getStyleClass().add("tab-titulo");
        Label descripcion = new Label("Simulación urbana");
        descripcion.getStyleClass().add("status-item");
        Region espacio = new Region();
        HBox.setHgrow(espacio, Priority.ALWAYS);
        selectorTema = new ComboBox<>();
        selectorTema.getItems().setAll("Modo oscuro", "Modo claro");
        selectorTema.getSelectionModel().selectFirst();
        selectorTema.setOnAction(event -> aplicarTema(selectorTema.getValue()));
        btnPanelControl = new Button("Ocultar panel");
        btnPanelControl.setOnAction(event -> {
            boolean visible = root.getRight() != null;
            root.setRight(visible ? null : panelControl);
            btnPanelControl.setText(visible ? "Mostrar panel" : "Ocultar panel");
        });
        Button btnMicros = new Button("Gestión de micros");
        btnMicros.setOnAction(event -> abrirVentanaMicros());
        Button abrirGeoJson = new Button("Abrir GeoJSON");
        abrirGeoJson.setOnAction(event -> seleccionarGeoJson());
        toolbar.getChildren().addAll(titulo, descripcion, espacio,
                new Label("Tema:"), selectorTema, abrirGeoJson,
                btnMicros, btnPanelControl);
        return toolbar;
    }

    private void aplicarTema(String tema) {
        if (root.getScene() == null) return;
        aplicarTema(root.getScene(), tema);
        if (ventanaMicros != null && ventanaMicros.getScene() != null) {
            aplicarTema(ventanaMicros.getScene(), tema);
        }
    }

    private void aplicarTema(javafx.scene.Scene escena, String tema) {
        String recurso = "Modo claro".equals(tema)
                ? "/estilos/estilo-claro.css" : "/estilos/estilo.css";
        var hoja = getClass().getResource(recurso);
        if (hoja == null) {
            System.err.println("[MainView] No se encontró la hoja de estilo: " + recurso);
            return;
        }
        var oscura = getClass().getResource("/estilos/estilo.css");
        var clara = getClass().getResource("/estilos/estilo-claro.css");
        if (oscura != null) escena.getStylesheets().remove(oscura.toExternalForm());
        if (clara != null) escena.getStylesheets().remove(clara.toExternalForm());
        escena.getStylesheets().add(hoja.toExternalForm());
    }

    private void seleccionarGeoJson() {
        FileChooser selector = new FileChooser();
        selector.setTitle("Cargar la red vial GeoJSON");
        selector.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("GeoJSON", "*.geojson", "*.json"));
        java.io.File archivo = selector.showOpenDialog(root.getScene().getWindow());
        if (archivo != null) mapa.cargarMapa(archivo.toPath());
    }

    private void abrirVentanaMicros() {
        if (ventanaMicros == null) {
            ventanaMicros = new Stage();
            ventanaMicros.setTitle("Gestión del transporte público - Juliaca");
            ventanaMicros.initModality(Modality.NONE);
            ventanaMicros.setScene(new javafx.scene.Scene(simulacion.crearVentanaMicros(),
                    560, 760));
            aplicarTema(ventanaMicros.getScene(), selectorTema.getValue());
            ventanaMicros.setMinWidth(480);
            ventanaMicros.setMinHeight(600);
        }
        if (root.getScene() != null && ventanaMicros.getOwner() == null) {
            ventanaMicros.initOwner(root.getScene().getWindow());
        }
        ventanaMicros.show();
        ventanaMicros.toFront();
    }

    private VBox crearTabReferencias() {
        VBox contenido = new VBox(12);
        contenido.setPadding(new Insets(15));
        contenido.getStyleClass().add("tab-contenido");
        Label titulo = new Label("Puntos de referencia");
        titulo.getStyleClass().add("tab-titulo");
        Label ayuda = new Label(
                "Escribe un nombre y pulsa el botón; después, haz clic en el mapa para colocar el marcador.");
        ayuda.setWrapText(true);
        nombreReferencia = new TextField();
        nombreReferencia.setPromptText("Ej.: Mercado San José");
        Button colocar = new Button("Colocar en el mapa");
        colocar.setMaxWidth(Double.MAX_VALUE);
        colocar.setOnAction(event -> {
            if (nombreReferencia.getText().isBlank()) {
                nombreReferencia.setPromptText("Debes escribir un nombre");
                return;
            }
            mapa.activarColocacionReferencia(nombreReferencia.getText());
            ayuda.setText("Modo de colocación activo: haz clic en la ubicación deseada.");
        });
        mapa.setOnReferenciaAgregada(referencia -> {
            simulacion.agregarPuntoReferencia(referencia);
            nombreReferencia.clear();
            ayuda.setText("Marcador agregado. Puedes colocar otro punto.");
        });
        listaReferencias.setPlaceholder(new Label("Aún no hay puntos agregados."));
        listaReferencias.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
            }
        });
        VBox.setVgrow(listaReferencias, Priority.ALWAYS);
        contenido.getChildren().addAll(titulo, ayuda, nombreReferencia, colocar,
                new Separator(), listaReferencias);
        return contenido;
    }

    private void actualizarListaReferencias(List<MarcadorReferencia> referencias) {
        listaReferencias.getItems().setAll(referencias.stream()
                .map(referencia -> String.format("%s (%.5f, %.5f)",
                        referencia.nombre(), referencia.lon(), referencia.lat())).toList());
    }

    // ==================== TAB MAPA ====================
    private VBox crearTabMapa() {
        VBox cont = new VBox(12);
        cont.setPadding(new Insets(15));
        cont.getStyleClass().add("tab-contenido");

        Button btnZoomIn  = new Button("+");
        Button btnZoomOut = new Button("\u2212");
        Button btnReset   = new Button("Restablecer");
        btnZoomIn.setPrefWidth(50);
        btnZoomOut.setPrefWidth(50);
        btnReset.setPrefWidth(80);

        btnZoomIn.setOnAction(e -> { mapa.zoomIn(); actualizarStatus(); });
        btnZoomOut.setOnAction(e -> { mapa.zoomOut(); actualizarStatus(); });
        btnReset.setOnAction(e -> { mapa.resetVistaPublico(); actualizarStatus(); });

        HBox filaZoom = new HBox(8, btnZoomOut, btnZoomIn, btnReset);
        filaZoom.setAlignment(Pos.CENTER_LEFT);

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

        VBox controlesNavegacion = new VBox(10);
        controlesNavegacion.getChildren().addAll(
                new Label("Zoom y encuadre"), filaZoom,
                new Separator(), new Label("Centrado rápido"),
                new VBox(6, btnPlaza, btnTerminal, btnUniversidad, btnMercado));

        String[] tiposBase = {
                "motorway", "motorway_link", "trunk", "trunk_link",
                "primary", "primary_link", "secondary", "secondary_link",
                "tertiary", "tertiary_link", "unclassified", "residential",
                "living_street", "service", "services", "footway", "pedestrian",
                "path", "steps", "track", "cycleway", "construction", "proposed", "raceway"
        };

        VBox filaFiltros = new VBox(4);
        for (String tipo : tiposBase) {
            CheckBox cb = new CheckBox(etiquetaVia(tipo));
            cb.setSelected(true);
            cb.getStyleClass().add("checkbox-tipo");
            cb.selectedProperty().addListener((o, a, b) -> {
                mapa.setTipoVisible(tipo, b);
                actualizarStatus();
            });
            filaFiltros.getChildren().add(cb);
        }

        CheckBox heatmap = new CheckBox("Mapa de calor de congestión");
        heatmap.selectedProperty().addListener((observable, anterior, activo) ->
                mapa.setHeatmapActivo(activo));
        VBox leyenda = new VBox(8);
        leyenda.getChildren().addAll(
                heatmap,
                crearItemLeyenda("#3FAF7F", "Flujo libre"),
                crearItemLeyenda("#E9C46A", "Congestion moderada"),
                crearItemLeyenda("#E76F51", "Congestion critica"),
                new Separator(),
                crearItemLeyenda("#E892A2", "Troncal"),
                crearItemLeyenda("#FCD6A4", "Principal"),
                crearItemLeyenda("#F7FABD", "Secundaria"),
                crearItemLeyenda("#FFFFFF", "Terciaria"),
                crearItemLeyenda("#AAAAAA", "Residencial")
        );

        Accordion secciones = new Accordion(
                new TitledPane("Navegación y zoom", controlesNavegacion),
                new TitledPane("Filtros de vías", filaFiltros),
                new TitledPane("Capas y leyenda", leyenda));
        secciones.setExpandedPane(secciones.getPanes().getFirst());
        cont.getChildren().add(secciones);

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

    // ==================== TAB RUTAS ====================
    private VBox crearTabRutas() {
        VBox cont = new VBox(12);
        cont.setPadding(new Insets(15));
        cont.getStyleClass().add("tab-contenido");

        Label lblTitulo = new Label("Ruta óptima (Dijkstra / A*)");
        lblTitulo.getStyleClass().add("tab-titulo");

        Label lblDesc = new Label(
                "1) Pulsa uno de los botones.\n" +
                "2) Haz clic en el mapa para fijar el punto.\n" +
                "3) Calcula la ruta.");
        lblDesc.getStyleClass().add("tab-descripcion");
        lblDesc.setWrapText(true);

        // Algoritmo
        Label lblAlgoritmo = new Label("Algoritmo");
        lblAlgoritmo.getStyleClass().add("tab-titulo");
        cbModo = new ComboBox<>();
        cbModo.getItems().addAll("A* (más rápido)", "Dijkstra (óptimo garantizado)");
        cbModo.getSelectionModel().select(0);

        // Botones origen/destino
        btnOrigen  = new Button("\uD83D\uDCCD Elegir origen");
        btnDestino = new Button("\uD83D\uDCCD Elegir destino");
        btnOrigen.setMaxWidth(Double.MAX_VALUE);
        btnDestino.setMaxWidth(Double.MAX_VALUE);

        btnOrigen.setOnAction(e -> {
            mapa.limpiarRuta();
            simulacion.aplicarRutaPreferida(null);
            mapa.activarModoSeleccion(true);
            btnOrigen.setText("Clic en el mapa: ORIGEN...");
        });

        btnDestino.setOnAction(e -> {
            if (mapa.getMarcadorOrigen() == null) {
                lblResultadoRuta.setText("Primero elige el origen.");
                return;
            }
            mapa.activarModoSeleccion(true);
            btnDestino.setText("Clic en el mapa: DESTINO...");
        });

        // Notificaciones del mapa cuando se fijan puntos
        mapa.setOnSeleccion(fase -> Platform.runLater(() -> {
            if (fase == 1) {
                btnOrigen.setText("\u2705 Origen fijado");
                btnDestino.setText("\uD83D\uDCCD Elegir destino");
            } else {
                btnDestino.setText("\u2705 Destino fijado");
            }
        }));

        // Opciones
        chkAlternativa = new CheckBox("Incluir ruta alternativa");

        // Calcular
        btnCalcularRuta = new Button("\u25B6  Calcular ruta");
        btnCalcularRuta.setMaxWidth(Double.MAX_VALUE);
        btnCalcularRuta.setOnAction(e -> calcularRuta());

        Button btnLimpiar = new Button("Borrar ruta");
        btnLimpiar.setMaxWidth(Double.MAX_VALUE);
        btnLimpiar.setOnAction(e -> {
            mapa.limpiarRuta();
            simulacion.aplicarRutaPreferida(null);
            mapa.activarModoSeleccion(false);
            btnOrigen.setText("\uD83D\uDCCD Elegir origen");
            btnDestino.setText("\uD83D\uDCCD Elegir destino");
            lblResultadoRuta.setText("");
        });

        // Resultado
        Label lblRes = new Label("Resultado");
        lblRes.getStyleClass().add("tab-titulo");
        lblResultadoRuta = new Label("");
        lblResultadoRuta.getStyleClass().add("tab-descripcion");
        lblResultadoRuta.setWrapText(true);

        cont.getChildren().addAll(
                lblTitulo, lblDesc,
                new Separator(),
                lblAlgoritmo, cbModo,
                btnOrigen, btnDestino,
                chkAlternativa,
                btnCalcularRuta, btnLimpiar,
                new Separator(),
                lblRes, lblResultadoRuta
        );

        ScrollPane scroll = new ScrollPane(cont);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("scroll-panel");
        VBox wrapper = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return wrapper;
    }

    private void calcularRuta() {
        var grafo = mapa.getGrafo();
        Nodo origen = mapa.getMarcadorOrigen();
        Nodo destino = mapa.getMarcadorDestino();

        if (grafo == null) {         lblResultadoRuta.setText("El grafo todavía no se ha cargado."); return; }
        if (origen == null || destino == null) {
            lblResultadoRuta.setText("Debes fijar el origen y el destino en el mapa.");
            return;
        }

        Pathfinder.Modo modo = cbModo.getSelectionModel().getSelectedIndex() == 0
                ? Pathfinder.Modo.A_ESTRELLA : Pathfinder.Modo.DIJKSTRA;
        boolean incluirAlternativa = chkAlternativa.isSelected();
        btnCalcularRuta.setDisable(true);
        lblResultadoRuta.setText("Calculando ruta...");

        Task<List<RutaResult>> tarea = new Task<>() {
            @Override
            protected List<RutaResult> call() {
                Pathfinder pathfinder = new Pathfinder(grafo);
                pathfinder.setModo(modo);
                RutaResult principal = pathfinder.ruta(origen, destino);
                if (principal == null) return List.of();
                if (!incluirAlternativa) return List.of(principal);
                List<RutaResult> resultados = pathfinder.rutasAlternativas(origen, destino, 2);
                if (resultados.isEmpty()) return List.of(principal);
                return resultados;
            }
        };
        tarea.setOnSucceeded(event -> {
            btnCalcularRuta.setDisable(false);
            List<RutaResult> resultados = tarea.getValue();
            if (resultados.isEmpty()) {
                lblResultadoRuta.setText("No existe ruta entre los puntos elegidos.");
                return;
            }
            RutaResult principal = resultados.get(0);
            mapa.setRuta(principal);
            simulacion.aplicarRutaPreferida(principal);
            StringBuilder texto = new StringBuilder("Principal: ").append(principal.resumen());
            if (resultados.size() > 1) {
                texto.append("\nAlternativa: ").append(resultados.get(1).resumen());
            }
            lblResultadoRuta.setText(texto.toString());
        });
        tarea.setOnFailed(event -> {
            btnCalcularRuta.setDisable(false);
            lblResultadoRuta.setText("Error calculando la ruta: "
                    + tarea.getException().getMessage());
        });
        Thread hilo = new Thread(tarea, "calculador-rutas");
        hilo.setDaemon(true);
        hilo.start();
    }

    private String etiquetaVia(String tipo) {
        return switch (tipo) {
            case "motorway" -> "Autopista";
            case "motorway_link" -> "Enlace de autopista";
            case "trunk" -> "Vía troncal";
            case "trunk_link" -> "Enlace troncal";
            case "primary" -> "Vía principal";
            case "primary_link" -> "Enlace principal";
            case "secondary" -> "Vía secundaria";
            case "secondary_link" -> "Enlace secundario";
            case "tertiary" -> "Vía terciaria";
            case "tertiary_link" -> "Enlace terciario";
            case "unclassified" -> "Vía sin clasificar";
            case "residential" -> "Vía residencial";
            case "living_street" -> "Calle residencial";
            case "service" -> "Vía de servicio";
            case "services" -> "Servicios";
            case "footway" -> "Sendero peatonal";
            case "pedestrian" -> "Zona peatonal";
            case "path" -> "Camino";
            case "steps" -> "Escaleras";
            case "track" -> "Vía sin pavimentar";
            case "cycleway" -> "Ciclovía";
            case "construction" -> "En construcción";
            case "proposed" -> "Proyectada";
            case "raceway" -> "Circuito";
            default -> tipo;
        };
    }

    // ==================== STATUS BAR ====================
    private HBox crearStatusBar() {
        HBox bar = new HBox(20);
        bar.setPadding(new Insets(6, 15, 6, 15));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");

        lblCoords   = new Label("Long.: --  Lat.: --");
        lblZoom     = new Label("Zoom: --");
        lblVisibles = new Label("Visibles: --");

        lblCoords.getStyleClass().add("status-item");
        lblZoom.getStyleClass().add("status-item");
        lblVisibles.getStyleClass().add("status-item");

        lblVehiculos = statusLabel("Vehículos: 0");
        lblHora = statusLabel("Hora: 06:00");
        lblEstado = statusLabel("Estado: Cargando");
        bar.getChildren().addAll(
                lblCoords,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                lblZoom,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                lblVisibles,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                lblVehiculos,
                lblHora,
                lblEstado
        );
        HBox.setHgrow(bar, Priority.ALWAYS);
        return bar;
    }

    private Label statusLabel(String texto) {
        Label label = new Label(texto);
        label.getStyleClass().add("status-item");
        return label;
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

    public void cerrar() {
        if (ventanaMicros != null) ventanaMicros.close();
        mapa.cerrar();
        simulacion.cerrar();
    }
}