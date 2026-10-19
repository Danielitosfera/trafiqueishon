package trafiqueishon.ui;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import trafiqueishon.geo.PieroGCiudadMapa;
import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.io.*;
import trafiqueishon.ruta.*;
import trafiqueishon.simulacion.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

/**
 * Ventana principal de Trafiqueishon.
 *
 * <p>Arquitectura (MVC por responsabilidad INDECOPI):</p>
 * <ul>
 *   <li><b>Vista</b> (LuisY, esta clase): construye controles, pinta Labels/Canvas/ListViews,
 *       abre diálogos y {@code FileChooser}, y reenvía cada acción a la lógica.</li>
 *   <li><b>Lógica</b> (EberthM): {@link EberthMGestorServicios} (recalcular rutas, fusionar,
 *       colores, trayectos), {@link EberthMMotorSimulacion} y {@link EberthMAplicadorEventos}.</li>
 *   <li><b>Geo</b> (PieroG): grafo vial, pathfinder y parser del GeoJSON de calles.</li>
 *   <li><b>Datos</b> (BetoH/DanielT): {@link BetoHFuenteMapa} (dónde están/guardan los mapas),
 *       {@link BetoHServicioPuntos}, lectores/escritores de puntos,
 *       {@link DanielTImportadorMapa} (GeoJSON crudo de Overpass a {@code puntos.json}) y exportadores.</li>
 * </ul>
 *
 * <p>Hilos:</p>
 * <ul>
 *   <li><b>JavaFX</b>: solo interfaz. Nunca calcula rutas ni avanza la simulación.</li>
 *   <li><b>motor-simulacion</b> ({@link EberthMMotorSimulacion}): simulador y costos del
 *       grafo (eventos, hora punta). El mapa lee su foto en cada fotograma.</li>
 *   <li><b>calculo-rutas</b>: Dijkstra/A* al crear o restaurar rutas, trayectos
 *       y exportaciones PDF; los resultados vuelven con {@code Platform.runLater}.</li>
 * </ul>
 *
 * <p>Diseño defensivo: ningún listener del usuario debería poder tumbar la app.
 * Las acciones pasan por {@link #ejecutarSeguro(String, Runnable)}.</p>
 */
public class LuisYVentanaPrincipal {

    private static final Logger LOG = Logger.getLogger(LuisYVentanaPrincipal.class.getName());
    private static final Preferences PREFERENCIAS =
            Preferences.userNodeForPackage(LuisYVentanaPrincipal.class);
    private static final String PREFERENCIA_TEMA_CLARO = "tema-claro";
    private static final String PREFERENCIA_CIUDAD = "ciudad";

    // ===== Infraestructura de la ventana =====
    private final BorderPane root = new BorderPane();
    private final StackPane capaCentral = new StackPane();
    private final BetoHFuenteMapa fuenteMapa = new BetoHFuenteMapa();
    private final LuisYMapaRutasCanvas mapa = new LuisYMapaRutasCanvas(fuenteMapa);
    private final LuisYPanelParadas panelParadas = new LuisYPanelParadas();
    private final LuisYVistaCroquis vistaCroquis = new LuisYVistaCroquis(mapa);
    private final VBox barraSuperior = new VBox();
    private HBox barraEstado;
    private final Accordion acordeon = new Accordion();
    private ScrollPane panelLateral;
    private Button botonSalirPresentacion;

    // ===== Controles: mapas =====
    private final ComboBox<PieroGCiudadMapa> selectorCiudad = new ComboBox<>();
    private final Label lblInfoMapa = new Label("Sin mapa cargado.");
    private final CheckBox chkCroquis = new CheckBox("Vista croquis (fondo blanco en tema claro)");
    private final CheckBox chkMapaCalor = new CheckBox("Mapa de calor de tráfico");
    private final ComboBox<LuisYMapaRutasCanvas.EtiquetasPuntos> selectorEtiquetas = new ComboBox<>();
    private boolean ignorarCambioCiudad;

    // ===== Controles: rutas y puntos =====
    private final ObservableList<LuisYPuntoImportante> puntosObservables = FXCollections.observableArrayList();
    private final FilteredList<LuisYPuntoImportante> puntosFiltrados = new FilteredList<>(puntosObservables);
    private final ListView<LuisYPuntoImportante> listaPuntos = new ListView<>(puntosFiltrados);
    private final TextField filtroPuntos = new TextField();
    private final ListView<String> listaTrayectos = new ListView<>();
    private final VBox filasRutasMicros = new VBox(6);
    private final ComboBox<EberthMServicioMicro> selectorMicro = new ComboBox<>();
    private final ToggleButton btnModoIdaVuelta = new ToggleButton("Modo Ida / Vuelta");
    private final CheckBox chkIda = new CheckBox("Ida (azul)");
    private final CheckBox chkVuelta = new CheckBox("Vuelta (roja)");

    // ===== Controles: configuración de ciudad =====
    private final BetoHRepositorioConfigCiudades repositorioConfig = new BetoHRepositorioConfigCiudades();
    private final Map<String, EberthMParametrosCiudad> parametrosPorCiudad = cargarConfigCiudades();
    private EberthMParametrosCiudad parametrosCiudad;
    private final TextField campoPoblacion = new TextField();
    private final Spinner<Double> spinnerTransporte = new Spinner<>(0.0, 100.0, 60.0, 1.0);
    private final TextField campoHorasPunta = new TextField();
    private final Spinner<Double> spinnerLluvia = new Spinner<>(0.0, 100.0, 20.0, 1.0);
    private final ComboBox<EberthMParametrosCiudad.TasaAccidentes> selectorAccidentes = new ComboBox<>();
    private final Spinner<Double> spinnerEstudiantes = new Spinner<>(0.0, 100.0, 25.0, 1.0);
    private final Spinner<Double> spinnerTrabajadores = new Spinner<>(0.0, 100.0, 50.0, 1.0);
    private final Spinner<Double> spinnerJubilados = new Spinner<>(0.0, 100.0, 25.0, 1.0);
    private final Spinner<Integer> spinnerCapacidadParada = new Spinner<>(1,
            EberthMParametrosCiudad.CAPACIDAD_PARADA_MAXIMA,
            EberthMParametrosCiudad.CAPACIDAD_PARADA_POR_DEFECTO, 10);
    private final TextField campoHorasPuntaFinSemana = new TextField();
    private final ComboBox<EberthMTipoDia> selectorTipoDia = new ComboBox<>();
    private final Label lblAvisoParametros = new Label();
    private final CheckBox chkEventosOrganicos =
            new CheckBox("Activar Eventos Orgánicos (Según Parámetros de Ciudad)");

    // ===== Controles: simulación y eventos =====
    private final ListView<EberthMEventoTrafico> listaEventos = new ListView<>();
    private final Label lblEstado = new Label("Cargando red vial...");
    private final Label lblCoords = new Label("Long.: --  Lat.: --");
    private final Label lblZoom = new Label("Zoom: --");
    private final Label lblInfoVista = new Label("");
    private final Label lblRelojStatus = new Label("Hora: --:--");
    private final Label lblHoraSimulada = new Label("Hora simulada: --:--");
    private final Label lblEstadisticas = new Label("Sin simulación activa.");
    private final ProgressIndicator progreso = new ProgressIndicator();
    private final ToggleGroup grupoEventosLocales = new ToggleGroup();
    private final ToggleButton toggleHoraPunta = new ToggleButton("🚌  Hora Punta");
    private final TextField nombreEvento = new TextField();
    private final Spinner<Integer> pasajerosSimulados = new Spinner<>(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(
                    LuisYDialogoRutaMicro.MIN_PASAJEROS, EberthMServicioMicro.MAX_PASAJEROS, 120));
    private final Spinner<Integer> horaInicio = new Spinner<>(0, 23, 7);
    private final Spinner<Integer> minutoInicio = new Spinner<>(0, 59, 0);
    private final Label resumenPasajeros = new Label();
    private final ComboBox<Integer> escalaDemanda = new ComboBox<>();
    private final ComboBox<Double> velocidadSimulacion = new ComboBox<>();
    private boolean temaClaro;

    // ===== Servicios / lógica =====
    private final LuisYGestorTrayectos gestor = new LuisYGestorTrayectos();
    private final EberthMMotorSimulacion motor = new EberthMMotorSimulacion();
    private final ExecutorService calculos = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "calculo-rutas");
        t.setDaemon(true);
        return t;
    });
    private final EberthMGestorEventos gestorEventos = new EberthMGestorEventos();
    private final BetoHLectorPuntosJson lectorJson = new BetoHLectorPuntosJson();
    private final DanielTImportadorMapa importadorMapa = new DanielTImportadorMapa(fuenteMapa);
    private final BetoHLectorPuntosExcel lectorExcel = new BetoHLectorPuntosExcel();
    private BetoHRepositorioServiciosMicro repositorioMicros;
    private final DanielTExportadorGeoJson expGeoJson = new DanielTExportadorGeoJson();
    private final DanielTExportadorGpx expGpx = new DanielTExportadorGpx();
    private final DanielTExportadorCsv expCsv = new DanielTExportadorCsv();

    private PieroGCiudadMapa ciudadActual;
    /** Cambia con cada ciudad: descarta resultados asíncronos de la ciudad anterior. */
    private long generacionCiudad;
    private long versionEventos;
    private int trabajosEnCurso;
    private boolean cargandoMapa;
    private PieroGGrafoVial grafo;
    private final List<LuisYPuntoImportante> puntos = new ArrayList<>();
    private boolean puntosModificados;
    private final List<EberthMEventoTrafico> eventos = new ArrayList<>();
    private final List<EberthMServicioMicro> rutasMicros = new ArrayList<>();
    private final Set<String> rutasMicrosVisibles = new HashSet<>();
    private LuisYTrayecto trayectoPartida;
    private LuisYTrayecto trayectoRetorno;

    // ===== Estado =====
    private boolean colocandoPunto;
    private boolean colocandoEvento;
    /** Tipos de punto que los pasajeros pueden tener como destino (no paradas ni terminales). */
    private static final Set<LuisYPuntoImportante.TipoPunto> ES_POI = java.util.EnumSet.of(
            LuisYPuntoImportante.TipoPunto.POI, LuisYPuntoImportante.TipoPunto.EDUCACION,
            LuisYPuntoImportante.TipoPunto.INSTITUCION, LuisYPuntoImportante.TipoPunto.MERCADO,
            LuisYPuntoImportante.TipoPunto.TRANSPORTE, LuisYPuntoImportante.TipoPunto.SALUD,
            LuisYPuntoImportante.TipoPunto.PARQUE, LuisYPuntoImportante.TipoPunto.TURISMO,
            LuisYPuntoImportante.TipoPunto.DEPORTE, LuisYPuntoImportante.TipoPunto.PLAZA,
            LuisYPuntoImportante.TipoPunto.COMERCIO);
    private boolean presentacionActiva;
    private boolean panelVisibleAntesPresentacion = true;
    private long versionUiSimulacion = -1;
    private long ultimaActualizacionUi;

    /** Refresca reloj y estadísticas (5 veces por segundo) leyendo la foto del motor. */
    private final AnimationTimer relojUi = new AnimationTimer() {
        @Override
        public void handle(long ahora) {
            if (ahora - ultimaActualizacionUi < 200_000_000L) return;
            ultimaActualizacionUi = ahora;
            try {
                actualizarIndicadoresSimulacion();
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "Error al refrescar indicadores", t);
            }
        }
    };

    public LuisYVentanaPrincipal() {
        try {
            temaClaro = PREFERENCIAS.getBoolean(PREFERENCIA_TEMA_CLARO, false);
            construirUI();
            configurarCallbacks();
            relojUi.start();
            PieroGCiudadMapa inicial = fuenteMapa
                    .buscarCiudad(PREFERENCIAS.get(PREFERENCIA_CIUDAD, "juliaca"))
                    .orElse(PieroGCiudadMapa.JULIACA);
            if (!fuenteMapa.existe(inicial, BetoHFuenteMapa.CALLES)) inicial = PieroGCiudadMapa.JULIACA;
            activarCiudad(inicial);
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "Fallo al construir la ventana principal", t);
            Platform.runLater(() -> {
                Alert alerta = new Alert(Alert.AlertType.ERROR);
                alerta.setTitle("Error al iniciar");
                alerta.setHeaderText("No se pudo inicializar la interfaz");
                alerta.setContentText(t.getMessage() == null
                        ? t.getClass().getSimpleName() : t.getMessage());
                alerta.showAndWait();
            });
        }
    }

    // ==================================================================
    //  CONSTRUCCIÓN DE LA UI
    // ==================================================================

    private void construirUI() {
        root.getStyleClass().add("root-view");
        aplicarClaseTema();
        mapa.setTemaClaro(temaClaro);
        StackPane.setAlignment(panelParadas, Pos.TOP_RIGHT);
        StackPane.setMargin(panelParadas, new Insets(12));
        capaCentral.getChildren().addAll(vistaCroquis, panelParadas);
        root.setCenter(capaCentral);

        barraSuperior.getChildren().setAll(crearToolbar());
        root.setTop(barraSuperior);
        root.setLeft(crearPanelLateral());

        barraEstado = crearStatusBar();
        root.setBottom(barraEstado);

        root.setFocusTraversable(true);
        root.addEventFilter(KeyEvent.KEY_PRESSED, this::manejarAtajos);
    }

    private void configurarCallbacks() {
        chkIda.setSelected(true);
        chkVuelta.setSelected(true);
        chkIda.setOnAction(e -> ejecutarSeguro("sentidos", this::actualizarVisualizacionRutas));
        chkVuelta.setOnAction(e -> ejecutarSeguro("sentidos", this::actualizarVisualizacionRutas));
        btnModoIdaVuelta.setOnAction(e -> ejecutarSeguro("modo ida/vuelta", this::alternarModoIdaVuelta));
        selectorMicro.valueProperty().addListener((o, a, b) ->
                ejecutarSeguro("seleccionar micro", this::actualizarVisualizacionRutas));
        chkMapaCalor.setOnAction(e -> ejecutarSeguro("mapa de calor",
                () -> mapa.setMostrarCalor(chkMapaCalor.isSelected())));
        chkCroquis.setSelected(true);
        chkCroquis.setOnAction(e -> ejecutarSeguro("vista croquis",
                () -> vistaCroquis.setModoCroquis(chkCroquis.isSelected())));
        selectorEtiquetas.getItems().setAll(LuisYMapaRutasCanvas.EtiquetasPuntos.values());
        selectorEtiquetas.getSelectionModel().select(LuisYMapaRutasCanvas.EtiquetasPuntos.AUTOMATICO);
        selectorEtiquetas.valueProperty().addListener((o, a, b) -> mapa.setEtiquetasPuntos(b));

        refrescarSelectorCiudad();
        selectorCiudad.valueProperty().addListener((o, anterior, nueva) -> {
            if (ignorarCambioCiudad || nueva == null) return;
            if (nueva.esImportar()) {
                // El ítem especial nunca queda seleccionado: se vuelve a la ciudad actual.
                revertirSelectorCiudad(anterior);
                Platform.runLater(() -> ejecutarSeguro("importar mapa", this::importarMapaNuevo));
                return;
            }
            ejecutarSeguro("cambiar ciudad", () -> cambiarCiudad(nueva, anterior));
        });

        filtroPuntos.setPromptText("Buscar punto por nombre o tipo…");
        filtroPuntos.textProperty().addListener((o, a, texto) -> {
            String buscado = texto == null ? "" : texto.trim().toLowerCase(Locale.ROOT);
            puntosFiltrados.setPredicate(p -> buscado.isEmpty()
                    || p.nombre().toLowerCase(Locale.ROOT).contains(buscado)
                    || p.tipo().name().toLowerCase(Locale.ROOT).contains(buscado));
        });

        horaInicio.setEditable(true);
        minutoInicio.setEditable(true);
        horaInicio.valueProperty().addListener((o, a, b) -> actualizarHoraPrevia());
        minutoInicio.valueProperty().addListener((o, a, b) -> actualizarHoraPrevia());
        pasajerosSimulados.valueProperty().addListener((o, a, b) -> actualizarResumenPasajeros());

        toggleHoraPunta.selectedProperty().addListener((o, a, activa) ->
                ejecutarSeguro("hora punta", () -> alternarHoraPunta(activa)));

        escalaDemanda.getItems().setAll(1, 2, 5, 10, 20, 50, 100);
        escalaDemanda.setEditable(false);
        escalaDemanda.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Integer n) { return n == null ? "" : "x" + n; }
            @Override public Integer fromString(String s) { return s == null || s.length() < 2 ? 1 : Integer.valueOf(s.substring(1)); }
        });
        escalaDemanda.getSelectionModel().select(Integer.valueOf(1));
        escalaDemanda.valueProperty().addListener((o, a, b) -> actualizarResumenPasajeros());
        velocidadSimulacion.getItems().setAll(0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 30.0);
        velocidadSimulacion.setPrefWidth(95);
        velocidadSimulacion.getSelectionModel().select(Double.valueOf(1.0));
        velocidadSimulacion.valueProperty().addListener((o, a, valor) -> {
            if (valor == null) return;
            motor.ejecutar(s -> s.setVelocidadSimulacion(valor));
            lblEstado.setText(String.format("Velocidad de simulación: %.1fx", valor));
        });

        motor.setOnError(t -> Platform.runLater(() ->
                lblEstado.setText("Simulación pausada por un error; el estado se conserva.")));
        mapa.setFuenteVehiculos(motor::instantanea);
        mapa.setOnMapaCargado(() -> ejecutarSeguro("cargar mapa", this::alCargarMapa));
        mapa.setOnError(msg -> {
            cargandoMapa = false;
            actualizarProgreso();
            lblEstado.setText(msg);
            lblInfoMapa.setText(msg);
        });
        mapa.setOnInfoMapa(lblInfoVista::setText);
        mapa.setOnClic(this::procesarClicMapa);
        mapa.setOnMouseMoveCallback(lonlat -> {
            lblCoords.setText(String.format("Long.: %.5f  Lat.: %.5f", lonlat[0], lonlat[1]));
            lblZoom.setText(String.format("Zoom: %.1f", mapa.getZoom()));
        });

        gestorEventos.agregarListener(this::procesarEventosActualizados);
        motor.ejecutar(s -> s.setOyenteEventosOrganicos(
                cambio -> Platform.runLater(() -> alEventosOrganicos(cambio))));

        actualizarHoraPrevia();
        actualizarResumenPasajeros();
    }

    private void manejarAtajos(KeyEvent evento) {
        try {
            // No robar Tab/Espacio mientras el usuario escribe en un campo.
            boolean escribiendo = root.getScene() != null
                    && root.getScene().getFocusOwner() instanceof TextInputControl;
            if (evento.getCode() == KeyCode.TAB && !escribiendo) {
                alternarPanelLateral();
                evento.consume();
            } else if (evento.getCode() == KeyCode.F11
                    || (presentacionActiva && evento.getCode() == KeyCode.ESCAPE)) {
                alternarPresentacion();
                evento.consume();
            } else if (evento.getCode() == KeyCode.SPACE && !escribiendo) {
                ejecutarSeguro("alternar simulación", this::iniciarOReanudarSimulacion);
                evento.consume();
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Error en atajo de teclado", t);
        }
    }

    // ==================================================================
    //  TOOLBAR
    // ==================================================================

    private VBox crearToolbar() {
        MenuBar menuBar = crearMenuBar();

        HBox controles = new HBox(10);
        controles.setPadding(new Insets(7, 12, 7, 12));
        controles.setAlignment(Pos.CENTER_LEFT);
        controles.getStyleClass().add("toolbar");

        Label titulo = new Label("TRAFIQUEISHON");
        titulo.getStyleClass().add("titulo-app");

        selectorCiudad.setPrefWidth(130);
        selectorCiudad.setTooltip(new Tooltip("Ciudad: recarga calles y puntos de mapas/<ciudad>/"));
        HBox grupoCiudad = grupo(new Label("Ciudad"), selectorCiudad);

        Button play = crearBotonIcono("▶", "Iniciar / continuar (Espacio)",
                this::iniciarOReanudarSimulacion);
        Button pause = crearBotonIcono("⏸", "Pausar", this::pausarSimulacion);
        Button stop = crearBotonIcono("⏹", "Detener y limpiar", this::detenerSimulacion);
        Button reset = crearBotonIcono("↺", "Reiniciar simulación", () ->
                motor.ejecutar(EberthMSimuladorBuses::limpiar)
                        .whenComplete((v, e) -> Platform.runLater(this::iniciarSimulacion)));
        HBox grupoSimulacion = grupo(play, pause, stop, reset);
        HBox grupoVelocidad = grupo(new Label("Velocidad"), velocidadSimulacion);

        Button btnSoloMapa = new Button("Solo mapa");
        btnSoloMapa.setOnAction(e -> alternarPanelLateral());
        HBox grupoVista = grupo(btnSoloMapa);

        Button zoomOut = crearBotonIcono("−", "Alejar", mapa::zoomOut);
        Button zoomIn = crearBotonIcono("+", "Acercar", mapa::zoomIn);
        HBox grupoZoom = grupo(zoomOut, zoomIn);

        Region espacio = new Region();
        HBox.setHgrow(espacio, Priority.ALWAYS);

        controles.getChildren().addAll(
                titulo,
                new Separator(Orientation.VERTICAL),
                grupoCiudad,
                new Separator(Orientation.VERTICAL),
                grupoSimulacion,
                grupoVelocidad,
                new Separator(Orientation.VERTICAL),
                grupoVista,
                espacio,
                lblRelojStatus,
                new Separator(Orientation.VERTICAL),
                grupoZoom);
        lblRelojStatus.getStyleClass().add("reloj");

        return new VBox(menuBar, controles);
    }

    private MenuBar crearMenuBar() {
        Menu archivo = new Menu("Archivo");
        MenuItem importar = item("Importar ubicaciones JSON", this::cargarPuntosJson);
        MenuItem importarGeoJson = item("Extraer ubicaciones de las calles (GeoJSON)", this::cargarPuntosGeoJson);
        MenuItem importarExcel = item("Importar ubicaciones Excel", this::cargarPuntosExcel);
        MenuItem plantilla = item("Crear plantilla Excel", this::crearPlantillaExcel);
        MenuItem guardar = item("Guardar ubicaciones JSON", this::guardarPuntos);
        MenuItem salir = new MenuItem("Salir");
        salir.setOnAction(e -> Platform.exit());
        archivo.getItems().addAll(importar, importarGeoJson, importarExcel, plantilla,
                new SeparatorMenuItem(), guardar, new SeparatorMenuItem(), salir);

        Menu vistas = new Menu("Vistas");
        RadioMenuItem oscuro = new RadioMenuItem("Tema oscuro");
        RadioMenuItem claro = new RadioMenuItem("Tema claro");
        ToggleGroup temas = new ToggleGroup();
        oscuro.setToggleGroup(temas);
        claro.setToggleGroup(temas);
        oscuro.setSelected(!temaClaro);
        claro.setSelected(temaClaro);
        oscuro.setOnAction(e -> aplicarTema(false));
        claro.setOnAction(e -> aplicarTema(true));
        vistas.getItems().addAll(
                item("Modo presentación (F11)", this::alternarPresentacion),
                new SeparatorMenuItem(),
                oscuro, claro);

        Menu ayuda = new Menu("Ayuda");
        MenuItem teclado = new MenuItem("Tab panel · F11 presentación · Esc salir · Espacio play");
        teclado.setDisable(true);
        MenuItem mapas = item("¿Dónde poner los mapas de Puno y Lima?", this::mostrarAyudaMapas);
        ayuda.getItems().addAll(teclado, mapas);

        return new MenuBar(archivo, vistas, ayuda);
    }

    private MenuItem item(String texto, Runnable accion) {
        MenuItem menuItem = new MenuItem(texto);
        menuItem.setOnAction(e -> ejecutarSeguro("acción '" + texto + "'", accion));
        return menuItem;
    }

    private Button crearBotonIcono(String icono, String tooltip, Runnable accion) {
        Button b = new Button(icono);
        b.setTooltip(new Tooltip(tooltip));
        b.setOnAction(e -> ejecutarSeguro("acción '" + tooltip + "'", accion));
        b.getStyleClass().add("boton-toolbar");
        return b;
    }

    private HBox grupo(Node... nodos) {
        HBox h = new HBox(6, nodos);
        h.setAlignment(Pos.CENTER_LEFT);
        h.getStyleClass().add("grupo-toolbar");
        return h;
    }

    // ==================================================================
    //  PANEL LATERAL (ACCORDION)
    // ==================================================================

    private ScrollPane crearPanelLateral() {
        TitledPane mapas = seccion("🗺  Mapas", crearSeccionMapas());
        TitledPane rutas = seccion("🛣  Rutas y Micros", crearSeccionRutas());
        TitledPane pois = seccion("📍  Puntos de Interés", crearSeccionPuntos());
        TitledPane ciudad = seccion("🏙  Configuración de Ciudad", crearSeccionConfiguracionCiudad());
        TitledPane simulacion = seccion("▶  Simulación", crearSeccionSimulacion());
        TitledPane eventosPane = seccion("⚡  Eventos", crearSeccionEventos());
        TitledPane exportar = seccion("⬇  Exportar", crearSeccionExportar());
        acordeon.getPanes().addAll(mapas, rutas, pois, ciudad, simulacion, eventosPane, exportar);
        acordeon.setExpandedPane(rutas);

        panelLateral = new ScrollPane(acordeon);
        panelLateral.setFitToWidth(true);
        panelLateral.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        panelLateral.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        panelLateral.setPrefWidth(420);
        panelLateral.setMinWidth(350);
        panelLateral.setMaxWidth(500);
        panelLateral.getStyleClass().add("panel-lateral");
        return panelLateral;
    }

    private TitledPane seccion(String titulo, VBox contenido) {
        contenido.setPadding(new Insets(12));
        contenido.getStyleClass().add("contenido-seccion");
        TitledPane pane = new TitledPane(titulo, contenido);
        pane.getStyleClass().add("seccion");
        pane.setAnimated(false);
        return pane;
    }

    private static Label subtitulo(String texto) {
        Label label = new Label(texto);
        label.getStyleClass().add("subtitulo");
        label.setWrapText(true);
        return label;
    }

    private static Label ayuda(String texto) {
        Label label = new Label(texto);
        label.getStyleClass().add("texto-secundario");
        label.setWrapText(true);
        return label;
    }

    private static GridPane rejillaBotones(Button... botones) {
        GridPane rejilla = new GridPane();
        rejilla.setHgap(6);
        rejilla.setVgap(6);
        ColumnConstraints col = new ColumnConstraints();
        col.setPercentWidth(50);
        rejilla.getColumnConstraints().addAll(col, col);
        for (int i = 0; i < botones.length; i++) {
            botones[i].setMaxWidth(Double.MAX_VALUE);
            rejilla.add(botones[i], i % 2, i / 2);
        }
        return rejilla;
    }

    private VBox crearSeccionMapas() {
        lblInfoMapa.setWrapText(true);
        lblInfoMapa.getStyleClass().add("texto-secundario");
        selectorEtiquetas.setMaxWidth(Double.MAX_VALUE);
        return new VBox(10,
                subtitulo("Red vial"),
                lblInfoMapa,
                rejillaBotones(
                        boton("Recargar mapa", () -> recargarCiudad()),
                        boton("Encuadrar mapa", mapa::resetVistaPublico)),
                ayuda("Cambia de ciudad desde la barra superior. Cada ciudad lee "
                        + "mapas/<ciudad>/calles.geojson (calles) y puntos.json (POIs)."),
                new Separator(),
                subtitulo("Visualización"),
                chkCroquis,
                chkMapaCalor,
                new Label("Nombres de puntos de interés"),
                selectorEtiquetas,
                ayuda("Con zoom lejano los puntos cercanos se agrupan en círculos con su "
                        + "cantidad; los nombres aparecen al acercarse y nunca se enciman."));
    }

    private VBox crearSeccionRutas() {
        // --- Micros con checkboxes (vista múltiple)
        HBox accionesMicros = new HBox(6,
                boton("➕ Crear ruta", this::crearRutaMicro),
                botonCompacto("Todas", () -> marcarTodasLasMicros(true)),
                botonCompacto("Ninguna", () -> marcarTodasLasMicros(false)));
        HBox.setHgrow(accionesMicros.getChildren().getFirst(), Priority.ALWAYS);

        // --- Ida / Vuelta
        selectorMicro.setMaxWidth(Double.MAX_VALUE);
        selectorMicro.setPromptText("Elige una micro…");
        selectorMicro.setCellFactory(l -> celdaMicro());
        selectorMicro.setButtonCell(celdaMicro());
        btnModoIdaVuelta.setMaxWidth(Double.MAX_VALUE);
        btnModoIdaVuelta.getStyleClass().add("toggle-destacado");
        HBox sentidos = new HBox(14, chkIda, chkVuelta);

        // --- Trayectos con todos los puntos
        listaTrayectos.setPlaceholder(new Label("Sin trayectos calculados."));
        listaTrayectos.setPrefHeight(90);

        actualizarFilasRutasMicro();
        return new VBox(10,
                subtitulo("Micros · marca las que quieres ver"),
                accionesMicros,
                filasRutasMicros,
                new Separator(),
                subtitulo("Vista Ida / Vuelta de una micro"),
                ayuda("Limpia el mapa y muestra solo la micro elegida: ida en azul, "
                        + "vuelta en rojo, con flechas de sentido."),
                selectorMicro,
                btnModoIdaVuelta,
                sentidos,
                boton("Enfocar ruta seleccionada", this::enfocarSeleccion),
                new Separator(),
                subtitulo("Trayecto que recorre todos los puntos"),
                rejillaBotones(
                        boton("Calcular PARTIDA", () -> calcular("PARTIDA")),
                        boton("Calcular RETORNO", () -> calcular("RETORNO"))),
                listaTrayectos,
                boton("Limpiar trayectos", this::limpiarTrayectos));
    }

    private VBox crearSeccionPuntos() {
        listaPuntos.setPlaceholder(new Label("Sin ubicaciones cargadas."));
        listaPuntos.setPrefHeight(190);
        listaPuntos.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(LuisYPuntoImportante punto, boolean vacio) {
                super.updateItem(punto, vacio);
                if (vacio || punto == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Region marca = new Region();
                marca.getStyleClass().add("muestra-punto");
                marca.setStyle("-fx-background-color: "
                        + LuisYPaletaColores.colorCategoria(punto.tipo()) + ";");
                setGraphic(marca);
                setText(punto.nombre() + "  ·  " + punto.tipo());
            }
        });
        return new VBox(10,
                subtitulo("Puntos de interés"),
                filtroPuntos,
                listaPuntos,
                rejillaBotones(
                        boton("Agregar en el mapa", () -> {
                            colocandoPunto = true;
                            grupoEventosLocales.selectToggle(null);
                            lblEstado.setText("Haz clic en el mapa para colocar la ubicación.");
                        }),
                        boton("Agregar por coordenadas", () -> editarPunto(null, null)),
                        boton("Editar seleccionado", () ->
                                editarPunto(listaPuntos.getSelectionModel().getSelectedItem(), null)),
                        boton("Eliminar seleccionado", this::eliminarPunto)),
                new Separator(),
                subtitulo("Importador"),
                rejillaBotones(
                        boton("Importar JSON", this::cargarPuntosJson),
                        boton("Importar Excel", this::cargarPuntosExcel),
                        boton("Extraer de calles", this::cargarPuntosGeoJson),
                        boton("Plantilla Excel", this::crearPlantillaExcel)),
                boton("Guardar ubicaciones JSON", this::guardarPuntos),
                ayuda("Importar reemplaza la lista actual. «Extraer de calles» crea los puntos "
                        + "a partir de las avenidas del calles.geojson de la ciudad."));
    }

    private VBox crearSeccionConfiguracionCiudad() {
        campoPoblacion.setPromptText("Ej. 280000");
        campoHorasPunta.setPromptText("07:00-09:00, 17:30-19:30");
        campoHorasPuntaFinSemana.setPromptText("11:00-14:00, 18:00-21:00");
        spinnerTransporte.setEditable(true);
        spinnerLluvia.setEditable(true);
        spinnerEstudiantes.setEditable(true);
        spinnerTrabajadores.setEditable(true);
        spinnerJubilados.setEditable(true);
        spinnerCapacidadParada.setEditable(true);
        spinnerTransporte.setMaxWidth(Double.MAX_VALUE);
        spinnerLluvia.setMaxWidth(Double.MAX_VALUE);
        selectorAccidentes.getItems().setAll(EberthMParametrosCiudad.TasaAccidentes.values());
        selectorAccidentes.setMaxWidth(Double.MAX_VALUE);
        lblAvisoParametros.setWrapText(true);
        lblAvisoParametros.getStyleClass().add("texto-secundario");

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        ColumnConstraints etiquetas = new ColumnConstraints();
        ColumnConstraints campos = new ColumnConstraints();
        campos.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(etiquetas, campos);
        String[] nombres = {"Población total", "Uso de transporte público (%)", "Horas punta",
                "Probabilidad de lluvia (%)", "Tasa de accidentes", "Estudiantes (%)",
                "Trabajadores (%)", "Jubilados (%)", "Capacidad máxima por parada",
                "Horas punta sáb/dom/feriado"};
        Node[] controles = {campoPoblacion, spinnerTransporte, campoHorasPunta, spinnerLluvia,
                selectorAccidentes, spinnerEstudiantes, spinnerTrabajadores, spinnerJubilados,
                spinnerCapacidadParada, campoHorasPuntaFinSemana};
        for (int i = 0; i < nombres.length; i++) {
            Label etiqueta = new Label(nombres[i]);
            etiqueta.setWrapText(true);
            form.add(etiqueta, 0, i);
            form.add(controles[i], 1, i);
            if (controles[i] instanceof Region r) r.setMaxWidth(Double.MAX_VALUE);
            GridPane.setHgrow(controles[i], Priority.ALWAYS);
        }
        return new VBox(10,
                subtitulo("Parámetros de la ciudad"),
                form,
                lblAvisoParametros,
                rejillaBotones(
                        boton("Aplicar", this::aplicarParametrosCiudad),
                        boton("Valores por defecto", this::restablecerParametrosCiudad)),
                ayuda("Al cambiar de ciudad se cargan valores realistas de Juliaca, Puno o Lima. "
                        + "Horas punta: rangos HH:mm-HH:mm separados por comas. Estudiantes + "
                        + "trabajadores + jubilados debe sumar 100 %. Ninguna parada acumula más "
                        + "pasajeros que su capacidad máxima: el resto se rinde y camina a otra."));
    }

    private Map<String, EberthMParametrosCiudad> cargarConfigCiudades() {
        Map<String, EberthMParametrosCiudad> mapa = new java.util.HashMap<>();
        try {
            mapa.putAll(repositorioConfig.cargar());
        } catch (IOException | RuntimeException ex) {
            LOG.log(Level.WARNING, "No se pudo leer config_ciudades.json; se usan los valores por defecto", ex);
        }
        return mapa;
    }

    private void guardarConfigCiudades() {
        try {
            repositorioConfig.guardar(parametrosPorCiudad);
        } catch (IOException | RuntimeException ex) {
            LOG.log(Level.WARNING, "No se pudo guardar config_ciudades.json", ex);
            lblAvisoParametros.setText("Aplicado, pero no se pudo guardar en disco: " + ex.getMessage());
        }
    }

    /** Parámetros de la ciudad activa (los de fábrica si aún no se editaron). */
    private void cargarParametrosCiudad(PieroGCiudadMapa ciudad) {
        parametrosCiudad = parametrosPorCiudad.computeIfAbsent(ciudad.id(),
                id -> EberthMParametrosCiudad.porDefecto(ciudad));
        mostrarParametros(parametrosCiudad);
        enviarParametrosAlMotor();
        lblAvisoParametros.setText("Valores de " + ciudad.nombre() + ".");
    }

    private void enviarParametrosAlMotor() {
        EberthMParametrosCiudad copia = parametrosCiudad.copia();
        motor.ejecutar(s -> s.setParametrosCiudad(copia));
    }

    /** Evento generado o caducado por el ecosistema (hilo de JavaFX): entra por el flujo normal de eventos. */
    private void alEventosOrganicos(EberthMSimuladorBuses.CambioEventosOrganicos cambio) {
        if (grafo == null) return;
        List<EberthMEventoTrafico> actuales = new ArrayList<>(eventos);
        boolean cambia = actuales.removeAll(cambio.expirados());
        actuales.addAll(cambio.nuevos());
        if (!cambia && cambio.nuevos().isEmpty()) return;
        gestorEventos.publicar(actuales);
        if (!cambio.nuevos().isEmpty()) {
            EberthMEventoTrafico ultimo = cambio.nuevos().getLast();
            lblEstado.setText("⚡ Evento orgánico: " + ultimo.nombre());
        }
    }

    private void mostrarParametros(EberthMParametrosCiudad p) {
        campoPoblacion.setText(String.valueOf(p.getPoblacionTotal()));
        spinnerTransporte.getValueFactory().setValue(p.getPorcentajeTransportePublico());
        campoHorasPunta.setText(p.getHorasPunta());
        campoHorasPuntaFinSemana.setText(p.getHorasPuntaFinSemana());
        spinnerLluvia.getValueFactory().setValue(p.getProbabilidadLluvia());
        selectorAccidentes.setValue(p.getTasaAccidentes());
        spinnerEstudiantes.getValueFactory().setValue(p.getPorcentajeEstudiantes());
        spinnerTrabajadores.getValueFactory().setValue(p.getPorcentajeTrabajadores());
        spinnerJubilados.getValueFactory().setValue(p.getPorcentajeJubilados());
        spinnerCapacidadParada.getValueFactory().setValue(p.getCapacidadMaximaParada());
    }

    private void aplicarParametrosCiudad() {
        if (ciudadActual == null) return;
        try {
            spinnerTransporte.commitValue(); // confirma el texto escrito a mano
            spinnerLluvia.commitValue();
            spinnerEstudiantes.commitValue();
            spinnerTrabajadores.commitValue();
            spinnerJubilados.commitValue();
            spinnerCapacidadParada.commitValue();
            EberthMParametrosCiudad nuevos = parametrosCiudad.copia();
            nuevos.setPoblacionTotal(Long.parseLong(campoPoblacion.getText().trim().replace(".", "")
                    .replace(",", "").replace(" ", "")));
            nuevos.setPorcentajeTransportePublico(spinnerTransporte.getValue());
            nuevos.setHorasPunta(campoHorasPunta.getText());
            nuevos.setHorasPuntaFinSemana(campoHorasPuntaFinSemana.getText());
            nuevos.setProbabilidadLluvia(spinnerLluvia.getValue());
            nuevos.setTasaAccidentes(selectorAccidentes.getValue());
            nuevos.setDemografia(spinnerEstudiantes.getValue(), spinnerTrabajadores.getValue(),
                    spinnerJubilados.getValue());
            nuevos.setCapacidadMaximaParada(spinnerCapacidadParada.getValue());
            parametrosCiudad = nuevos;
            parametrosPorCiudad.put(ciudadActual.id(), nuevos);
            enviarParametrosAlMotor();
            lblAvisoParametros.setText("Parámetros de " + ciudadActual.nombre() + " aplicados y guardados.");
            guardarConfigCiudades();
            lblEstado.setText("Configuración de " + ciudadActual.nombre() + " actualizada.");
        } catch (NumberFormatException ex) {
            lblAvisoParametros.setText("La población debe ser un número entero.");
        } catch (IllegalArgumentException ex) {
            lblAvisoParametros.setText(ex.getMessage());
        }
    }

    private void restablecerParametrosCiudad() {
        if (ciudadActual == null) return;
        parametrosCiudad = EberthMParametrosCiudad.porDefecto(ciudadActual);
        parametrosPorCiudad.put(ciudadActual.id(), parametrosCiudad);
        mostrarParametros(parametrosCiudad);
        enviarParametrosAlMotor();
        lblAvisoParametros.setText("Valores por defecto de " + ciudadActual.nombre() + " restaurados.");
        guardarConfigCiudades();
    }

    private ListCell<EberthMServicioMicro> celdaMicro() {
        return new ListCell<>() {
            @Override
            protected void updateItem(EberthMServicioMicro micro, boolean vacio) {
                super.updateItem(micro, vacio);
                if (vacio || micro == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Region marca = new Region();
                marca.getStyleClass().add("muestra-color");
                marca.setStyle("-fx-background-color: " + micro.color() + ";");
                setGraphic(marca);
                setText(micro.nombre());
            }
        };
    }

    private VBox crearSeccionSimulacion() {
        pasajerosSimulados.setEditable(true);
        resumenPasajeros.getStyleClass().add("texto-secundario");
        resumenPasajeros.setWrapText(true);
        horaInicio.setPrefWidth(80);
        minutoInicio.setPrefWidth(80);
        lblEstadisticas.setWrapText(true);
        lblEstadisticas.getStyleClass().add("texto-secundario");
        lblHoraSimulada.getStyleClass().add("reloj");
        selectorTipoDia.getItems().setAll(EberthMTipoDia.values());
        selectorTipoDia.setValue(EberthMTipoDia.LABORAL);
        selectorTipoDia.setMaxWidth(Double.MAX_VALUE);
        selectorTipoDia.valueProperty().addListener((o, antes, dia) -> {
            if (dia == null) return;
            motor.ejecutar(s -> s.setTipoDia(dia));
            lblEstado.setText("Tipo de día: " + dia + ". Cambian la demanda por parada, la "
                    + "congestión y los accidentes.");
        });
        chkEventosOrganicos.setWrapText(true);
        chkEventosOrganicos.selectedProperty().addListener((o, antes, activo) -> {
            motor.ejecutar(s -> s.setEventosOrganicos(activo));
            lblEstado.setText(activo
                    ? "Eventos orgánicos activados: la ciudad generará trancaderas, accidentes y lluvia sola."
                    : "Eventos orgánicos desactivados; los ya creados caducan solos.");
        });
        HBox hora = new HBox(8, new Label("Hora"), horaInicio, new Label("Min"), minutoInicio);
        hora.setAlignment(Pos.CENTER_LEFT);
        return new VBox(10,
                subtitulo("Reloj"),
                hora,
                lblHoraSimulada,
                lblEstadisticas,
                new Separator(),
                subtitulo("Tipo de día"),
                selectorTipoDia,
                ayuda("Sábado ≈ 70 % de la demanda de un día laboral; domingo y feriado ≈ 55 %, con "
                        + "picos más suaves. Colegios y oficinas casi se vacían; comercio y "
                        + "turismo suben. Valores estimados."),
                new Separator(),
                subtitulo("Ecosistema"),
                chkEventosOrganicos,
                ayuda("Cada minuto simulado se sortea un evento con los parámetros de "
                        + "«Configuración de Ciudad»: hora punta triplica las trancaderas, la tasa "
                        + "de accidentes fija los choques y la probabilidad de lluvia abre "
                        + "tormentas de radio variable. Caen en vías principales o secundarias y "
                        + "caducan solos."),
                new Separator(),
                ayuda("Iniciar, pausar, detener y reiniciar: botones de la barra superior (Espacio). "
                        + "La simulación corre en un hilo propio: la interfaz y el mapa "
                        + "siguen fluidos aunque haya miles de micros y eventos."),
                new Separator(),
                subtitulo("Demanda de pasajeros por salida"),
                pasajerosSimulados,
                new HBox(8, new Label("Escala de simulación (multiplicador de demanda)"), escalaDemanda),
                resumenPasajeros,
                ayuda("Factores: mañana 2.5x · mediodía 1.8x · tarde 2.8x · noche 0.2x. "
                        + "Capacidad: 18 pasajeros por micro."));
    }

    private VBox crearSeccionEventos() {
        nombreEvento.setPromptText("Nombre del evento (opcional)");
        listaEventos.setPlaceholder(new Label("Sin eventos activos."));
        listaEventos.setPrefHeight(160);
        return new VBox(10,
                subtitulo("Incidentes viales"),
                subtitulo("Eventos locales · elige uno y haz clic en el mapa"),
                crearGridEventosLocales(),
                nombreEvento,
                new Separator(),
                subtitulo("Eventos globales · afectan a toda la ciudad"),
                crearGridEventosGlobales(),
                new Separator(),
                subtitulo("Eventos activos"),
                listaEventos,
                rejillaBotones(
                        boton("Eliminar seleccionado", this::eliminarEvento),
                        boton("Eliminar todos", this::eliminarTodosLosEventos)),
                ayuda("Los cierres recalculan desvíos con A* en segundo plano, sin detener "
                        + "la simulación."));
    }

    private GridPane crearGridEventosLocales() {
        GridPane rejilla = new GridPane();
        rejilla.setHgap(6);
        rejilla.setVgap(6);
        ColumnConstraints col = new ColumnConstraints();
        col.setPercentWidth(50);
        rejilla.getColumnConstraints().addAll(col, col);
        EberthMTipoEvento[] tipos = {EberthMTipoEvento.ACCIDENTE, EberthMTipoEvento.TRANCADERA,
                EberthMTipoEvento.VENDEDORES_AMBULANTES, EberthMTipoEvento.VIA_CERRADA,
                EberthMTipoEvento.LLUVIA};
        String[] iconos = {"💥", "🚦", "🛒", "🚧", "🌧"};
        for (int i = 0; i < tipos.length; i++) {
            EberthMTipoEvento tipo = tipos[i];
            ToggleButton b = new ToggleButton(iconos[i] + "\n" + tipo);
            b.setToggleGroup(grupoEventosLocales);
            b.setUserData(tipo);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setMinHeight(58);
            b.setWrapText(true);
            b.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
            b.getStyleClass().add("boton-evento");
            rejilla.add(b, i % 2, i / 2);
        }
        // Seleccionar un botón arma el clic en el mapa; deseleccionarlo lo cancela.
        grupoEventosLocales.selectedToggleProperty().addListener((o, a, b) -> {
            colocandoEvento = b != null;
            if (b == null) return;
            colocandoPunto = false;
            lblEstado.setText(((EberthMTipoEvento) b.getUserData()).bloqueaNodo()
                    ? "Haz clic sobre la intersección afectada para colocar el evento."
                    : ((EberthMTipoEvento) b.getUserData()).esZonal()
                    ? "Haz clic en el centro de la lluvia (radio de 1 km)."
                    : "Haz clic sobre la vía afectada para colocar el evento.");
        });
        return rejilla;
    }

    private HBox crearGridEventosGlobales() {
        toggleHoraPunta.setMaxWidth(Double.MAX_VALUE);
        toggleHoraPunta.setMinHeight(40);
        toggleHoraPunta.getStyleClass().add("boton-evento");
        HBox.setHgrow(toggleHoraPunta, Priority.ALWAYS);
        return new HBox(6, toggleHoraPunta);
    }

    private VBox crearSeccionExportar() {
        Button multiple = boton("PDF · Vista múltiple", this::exportarPdfMultiple);
        Button individual = boton("PDF · Vista individual (Ida/Vuelta)", this::exportarPdfIndividual);
        multiple.getStyleClass().add("boton-primario");
        individual.getStyleClass().add("boton-primario");
        return new VBox(10,
                subtitulo("PDF en estilo croquis"),
                multiple,
                ayuda("Todas las micros marcadas en una sola lámina, cada una con su color "
                        + "(ida continua, vuelta discontinua) y la lista de rutas."),
                individual,
                ayuda("Solo la micro seleccionada: ida azul, vuelta roja y sus paraderos."),
                new Separator(),
                subtitulo("Otros formatos"),
                boton("GeoJSON, GPX, CSV, TXT o PDF por micro…", this::exportarRutas));
    }

    private void alternarPanelLateral() {
        ejecutarSeguro("alternar panel", () -> {
            boolean mostrar = root.getLeft() == null;
            root.setLeft(mostrar ? panelLateral : null);
        });
    }

    private void aplicarTema(boolean claro) {
        temaClaro = claro;
        aplicarClaseTema();
        mapa.setTemaClaro(claro);
        PREFERENCIAS.putBoolean(PREFERENCIA_TEMA_CLARO, claro);
    }

    private void aplicarClaseTema() {
        root.getStyleClass().removeAll("theme-dark", "theme-light");
        root.getStyleClass().add(temaClaro ? "theme-light" : "theme-dark");
    }

    private void alternarPresentacion() {
        ejecutarSeguro("modo presentación", () -> {
            presentacionActiva = !presentacionActiva;
            if (presentacionActiva) {
                panelVisibleAntesPresentacion = root.getLeft() != null;
                root.setTop(null);
                root.setLeft(null);
                root.setBottom(null);
                mostrarBotonSalirPresentacion();
            } else {
                root.setTop(barraSuperior);
                root.setLeft(panelVisibleAntesPresentacion ? panelLateral : null);
                root.setBottom(barraEstado);
                ocultarBotonSalirPresentacion();
            }
        });
    }

    private void mostrarBotonSalirPresentacion() {
        if (botonSalirPresentacion == null) {
            botonSalirPresentacion = new Button("✕ Salir (Esc)");
            botonSalirPresentacion.getStyleClass().add("boton-flotante");
            botonSalirPresentacion.setOnAction(e -> alternarPresentacion());
        }
        if (!capaCentral.getChildren().contains(botonSalirPresentacion)) {
            capaCentral.getChildren().add(botonSalirPresentacion);
            StackPane.setAlignment(botonSalirPresentacion, Pos.TOP_RIGHT);
            StackPane.setMargin(botonSalirPresentacion, new Insets(12));
        }
    }

    private void ocultarBotonSalirPresentacion() {
        if (botonSalirPresentacion != null) {
            capaCentral.getChildren().remove(botonSalirPresentacion);
        }
    }

    // ==================================================================
    //  CIUDADES
    // ==================================================================

    private void cambiarCiudad(PieroGCiudadMapa nueva, PieroGCiudadMapa anterior) {
        if (nueva.equals(ciudadActual)) return;
        if (!fuenteMapa.existe(nueva, BetoHFuenteMapa.CALLES)) {
            revertirSelectorCiudad(anterior);
            mostrarError("Mapa de " + nueva.nombre() + " no disponible",
                    "No se encontró calles.geojson para " + nueva.nombre() + ".\n\nColócalo en:\n• "
                            + String.join("\n• ", fuenteMapa.lugaresBuscados(nueva))
                            + "\n\nConsulta Ayuda → «¿Dónde poner los mapas de Puno y Lima?».");
            return;
        }
        if (puntosModificados && !confirmar("Cambiar de ciudad",
                "Hay cambios sin guardar en las ubicaciones de " + ciudadActual.nombre()
                        + ". ¿Descartarlos y abrir " + nueva.nombre() + "?")) {
            revertirSelectorCiudad(anterior);
            return;
        }
        activarCiudad(nueva);
    }

    /** Ciudades incluidas, las importadas por el usuario y al final el ítem «Importar». */
    private void refrescarSelectorCiudad() {
        ignorarCambioCiudad = true;
        try {
            PieroGCiudadMapa actual = ciudadActual;
            List<PieroGCiudadMapa> items = new ArrayList<>(PieroGCiudadMapa.DISPONIBLES);
            items.addAll(fuenteMapa.ciudadesImportadas());
            items.add(PieroGCiudadMapa.IMPORTAR);
            selectorCiudad.getItems().setAll(items);
            if (actual != null) selectorCiudad.setValue(actual);
        } finally {
            ignorarCambioCiudad = false;
        }
    }

    /** Elige un GeoJSON crudo, pide el nombre de la ciudad y lo guarda en la carpeta del usuario. */
    private void importarMapaNuevo() {
        File archivo = elegirArchivo("Importar mapa (GeoJSON de Overpass)", "GeoJSON",
                "*.geojson;*.json", false, null);
        if (archivo == null) return;
        TextInputDialog pregunta = new TextInputDialog();
        estilizarDialogo(pregunta);
        pregunta.setTitle("Importar nuevo mapa");
        pregunta.setHeaderText("Nombre de la nueva ciudad");
        pregunta.setContentText("Nombre:");
        Optional<String> nombre = pregunta.showAndWait();
        if (nombre.isEmpty() || nombre.get().isBlank()) return;
        String id = PieroGCiudadMapa.idDesdeNombre(nombre.get());
        if (id.isEmpty()) {
            mostrarError("Nombre no válido", "Usa letras o números en el nombre de la ciudad.");
            return;
        }
        if (PieroGCiudadMapa.personalizada(id, false).esIncluida()) {
            mostrarError("Nombre no disponible", "«" + nombre.get().trim()
                    + "» ya viene incluida en la aplicación. Elige otro nombre.");
            return;
        }
        if (fuenteMapa.buscarCiudad(id).isPresent()
                && !confirmar("Reemplazar mapa", "Ya existe un mapa llamado «" + id
                        + "». ¿Reemplazar sus calles con este archivo?")) {
            return;
        }
        String nombreCiudad = nombre.get();
        enSegundoPlano("Importando mapa de " + nombreCiudad.trim(), () -> {
            try {
                return importadorMapa.importarCiudad(archivo.toPath(), nombreCiudad);
            } catch (IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }, ciudad -> {
            refrescarSelectorCiudad();
            lblEstado.setText("Mapa «" + ciudad.nombre() + "» guardado en "
                    + fuenteMapa.carpetaUsuario(ciudad) + ". Cargando…");
            if (ciudad.equals(ciudadActual)) recargarCiudad();
            else cambiarCiudad(ciudad, ciudadActual);
        });
    }

    private void revertirSelectorCiudad(PieroGCiudadMapa anterior) {
        ignorarCambioCiudad = true;
        try {
            selectorCiudad.setValue(anterior != null ? anterior : ciudadActual);
        } finally {
            ignorarCambioCiudad = false;
        }
    }

    private void recargarCiudad() {
        if (ciudadActual != null) activarCiudad(ciudadActual);
    }

    /** Limpia todo el estado de la ciudad anterior y carga la nueva en segundo plano. */
    private void activarCiudad(PieroGCiudadMapa nueva) {
        ciudadActual = nueva;
        generacionCiudad++;
        PREFERENCIAS.put(PREFERENCIA_CIUDAD, nueva.id());
        revertirSelectorCiudad(nueva);
        cargarParametrosCiudad(nueva);

        motor.ejecutar(s -> {
            s.limpiar();
            s.actualizarEventos(List.of());
        });
        grafo = null;
        puntos.clear();
        puntosModificados = false;
        actualizarListaPuntos();
        eventos.clear();
        listaEventos.getItems().clear();
        mapa.setEventos(List.of());
        mapa.setViasAfectadas(List.of(), List.of());
        rutasMicros.clear();
        rutasMicrosVisibles.clear();
        gestor.limpiar();
        listaTrayectos.getItems().clear();
        trayectoPartida = null;
        trayectoRetorno = null;
        btnModoIdaVuelta.setSelected(false);
        actualizarFilasRutasMicro();
        // Nada de la ciudad anterior debe quedar en paneles, selecciones ni mapa.
        panelParadas.ocultar();
        listaPuntos.getSelectionModel().clearSelection();
        listaEventos.getSelectionModel().clearSelection();
        actualizarVisualizacionRutas();
        versionUiSimulacion = -1;
        repositorioMicros = BetoHRepositorioServiciosMicro.paraCiudad(nueva);

        lblInfoMapa.setText("Cargando " + nueva.nombre() + "…");
        lblEstado.setText("Cargando mapa de " + nueva.nombre() + "…");
        cargandoMapa = true;
        actualizarProgreso();
        mapa.cargarCiudad(nueva);
    }

    private void alCargarMapa() {
        cargandoMapa = false;
        actualizarProgreso();
        grafo = mapa.getGrafo();
        PieroGGrafoVial red = grafo;
        motor.ejecutar(s -> {
            s.limpiar();
            s.configurarGrafo(red);
            s.actualizarEventos(List.of());
        });
        String origen = fuenteMapa.localizar(ciudadActual, BetoHFuenteMapa.CALLES)
                .map(BetoHFuenteMapa.Origen::descripcion).orElse("?");
        lblInfoMapa.setText(ciudadActual.nombre() + ": " + mapa.getTotalVias() + " vías · "
                + grafo.numNodos() + " nodos · " + grafo.numEdges() + " tramos.\nArchivo: " + origen);
        lblEstado.setText("Red de " + ciudadActual.nombre() + " lista: " + grafo.numNodos()
                + " nodos, " + grafo.numEdges() + " tramos.");
        cargarPuntosCiudad();
        cargarRutasMicrosGuardadas();
    }

    private void mostrarAyudaMapas() {
        StringBuilder texto = new StringBuilder();
        for (PieroGCiudadMapa ciudad : PieroGCiudadMapa.DISPONIBLES) {
            texto.append(ciudad.nombre()).append(": ")
                    .append(fuenteMapa.localizar(ciudad, BetoHFuenteMapa.CALLES)
                            .map(o -> "✔ " + o.descripcion())
                            .orElse("✘ falta calles.geojson"))
                    .append('\n');
        }
        texto.append("\nCarpetas de búsqueda (en orden):\n• ")
                .append(String.join("\n• ", fuenteMapa.lugaresBuscados(PieroGCiudadMapa.LIMA)))
                .append("\n\nLima pesa cientos de MB: no la metas en el jar. Colócala en una de "
                        + "las carpetas externas (admite calles.geojson.gz) y ejecuta con "
                        + "mvn javafx:run -Dapp.xmx=2g");
        Alert alerta = new Alert(Alert.AlertType.INFORMATION, texto.toString(), ButtonType.OK);
        estilizarDialogo(alerta);
        alerta.setTitle("Mapas por ciudad");
        alerta.setHeaderText("Cada ciudad: mapas/<ciudad>/calles.geojson + puntos.json");
        alerta.showAndWait();
    }

    // ==================================================================
    //  TRABAJOS EN SEGUNDO PLANO
    // ==================================================================

    private void trabajoIniciado() {
        trabajosEnCurso++;
        actualizarProgreso();
    }

    private void trabajoTerminado() {
        trabajosEnCurso = Math.max(0, trabajosEnCurso - 1);
        actualizarProgreso();
    }

    private void actualizarProgreso() {
        progreso.setVisible(cargandoMapa || trabajosEnCurso > 0);
    }

    /**
     * Ejecuta {@code tarea} en el hilo de cálculo y entrega el resultado en el
     * hilo de JavaFX. Si entretanto se cambió de ciudad, el resultado se descarta.
     */
    private <T> void enSegundoPlano(String descripcion, Supplier<T> tarea, Consumer<T> alTerminar) {
        long generacion = generacionCiudad;
        lblEstado.setText(descripcion + "…");
        trabajoIniciado();
        CompletableFuture.supplyAsync(tarea, calculos).whenComplete((resultado, error) ->
                Platform.runLater(() -> {
                    trabajoTerminado();
                    if (generacion != generacionCiudad) return;
                    if (error != null) {
                        Throwable causa = error.getCause() != null ? error.getCause() : error;
                        LOG.log(Level.WARNING, "Falló: " + descripcion, causa);
                        lblEstado.setText("No se pudo completar «" + descripcion + "»: "
                                + (causa.getMessage() == null ? causa.getClass().getSimpleName()
                                : causa.getMessage()));
                        return;
                    }
                    ejecutarSeguro(descripcion, () -> alTerminar.accept(resultado));
                }));
    }

    // ==================================================================
    //  ACCIONES DE SIMULACIÓN
    // ==================================================================

    private void iniciarOReanudarSimulacion() {
        if (motor.instantanea().haySimulacion()) {
            motor.ejecutar(EberthMSimuladorBuses::reanudar);
            lblEstado.setText("Simulación reanudada.");
        } else {
            iniciarSimulacion();
        }
    }

    private void pausarSimulacion() {
        motor.ejecutar(EberthMSimuladorBuses::pausar);
        lblEstado.setText("Simulación pausada.");
    }

    private void detenerSimulacion() {
        limpiarSimulacion(true);
    }

    private record Resumen(boolean habia, int transportados, double ocupacion, int incidencias,
                           double segundos) {}

    private void limpiarSimulacion(boolean mostrarResultados) {
        motor.calcular(s -> {
            Resumen r = new Resumen(s.haySimulacion(), s.pasajerosTransportados(),
                    s.ocupacionPromedio() * 100, s.vehiculosConIncidencia(),
                    s.segundosTranscurridos());
            s.limpiar();
            return r;
        }).whenComplete((r, error) -> Platform.runLater(() -> {
            lblEstado.setText("Simulación detenida y estado reiniciado.");
            actualizarHoraPrevia();
            if (error != null || r == null || !mostrarResultados || !r.habia()) return;
            Alert resultados = new Alert(Alert.AlertType.INFORMATION);
            estilizarDialogo(resultados);
            resultados.setTitle("Resultados de simulación");
            resultados.setHeaderText("Resumen de la ejecución");
            resultados.setContentText("Abordajes simulados: " + r.transportados()
                    + "\nOcupación media de la flota: " + String.format("%.1f%%", r.ocupacion())
                    + "\nVehículos con ruta pendiente o avería: " + r.incidencias()
                    + "\nTiempo simulado: " + String.format("%.1f min", r.segundos() / 60));
            resultados.show();
        }));
    }

    private void iniciarSimulacion() {
        if (grafo == null) {
            lblEstado.setText("La red vial todavía no está lista.");
            return;
        }
        List<EberthMServicioMicro> seleccionadas = rutasMicros.stream()
                .filter(r -> rutasMicrosVisibles.contains(r.id()))
                .toList();
        if (seleccionadas.isEmpty()) {
            lblEstado.setText("Marca al menos una micro en «Rutas y Puntos».");
            return;
        }
        PieroGGrafoVial red = grafo;
        int hora = horaInicio.getValue();
        int minuto = minutoInicio.getValue();
        double velocidad = velocidadSimulacion.getValue() == null ? 1.0 : velocidadSimulacion.getValue();
        int escala = escalaDemanda.getValue() == null ? 1 : escalaDemanda.getValue();
        EberthMTipoDia dia = selectorTipoDia.getValue() == null
                ? EberthMTipoDia.LABORAL : selectorTipoDia.getValue();
        List<EberthMPoi> pois = puntos.stream()
                .filter(p -> ES_POI.contains(p.tipo()))
                .map(p -> new EberthMPoi(p.nombre(), p.lon(), p.lat(),
                        EberthMCategoriaPoi.desde(p.tipo())))
                .toList();
        lblEstado.setText("Preparando " + seleccionadas.size() + " servicios…");
        trabajoIniciado();
        // Hora punta y recálculo de trayectos se hacen en el hilo del motor:
        // modifican costos del grafo que los vehículos leen.
        motor.calcular(s -> {
            EberthMPerfilHoraPunta.aplicar(red, hora * 60 + minuto, dia);
            s.setTipoDia(dia);
            List<EberthMServicioMicro> actualizadas = EberthMGestorServicios.recalcular(red, seleccionadas);
            s.setVelocidadSimulacion(velocidad);
            s.setEscalaDemanda(escala);
            s.setPuntosInteres(pois);
            s.iniciar(actualizadas, hora, minuto);
            return actualizadas.size();
        }).whenComplete((cantidad, error) -> Platform.runLater(() -> {
            trabajoTerminado();
            if (error != null) {
                Throwable causa = error.getCause() != null ? error.getCause() : error;
                LOG.log(Level.SEVERE, "Error al iniciar simulación", causa);
                lblEstado.setText("No se pudo iniciar la simulación: " + causa.getMessage());
                return;
            }
            lblEstado.setText("Servicios activos: " + cantidad
                    + ". Salidas programadas desde ambos terminales, según frecuencia.");
        }));
    }

    private void actualizarIndicadoresSimulacion() {
        EberthMMotorSimulacion.Instantanea foto = motor.instantanea();
        if (foto.version() == versionUiSimulacion) return;
        versionUiSimulacion = foto.version();
        if (!foto.haySimulacion()) {
            lblEstadisticas.setText("Sin simulación activa.");
            actualizarHoraPrevia();
            return;
        }
        int minutos = foto.horaMinutos();
        String hhmm = String.format("%02d:%02d", minutos / 60, minutos % 60);
        lblHoraSimulada.setText((foto.ejecutando() ? "Hora simulada: " : "En pausa: ") + hhmm);
        lblRelojStatus.setText("Hora: " + hhmm + (foto.ejecutando() ? "" : " ⏸"));
        lblEstadisticas.setText(String.format(
                "Unidades en circulación: %d · Abordajes: %d%nOcupación media: %.0f%% · "
                        + "Incidencias: %d · Tiempo simulado: %.1f min",
                foto.vehiculos().size(), foto.pasajerosTransportados(),
                foto.ocupacionPromedio() * 100, foto.vehiculosConIncidencia(),
                foto.segundosSimulados() / 60));
    }

    private void actualizarHoraPrevia() {
        if (motor.instantanea().haySimulacion()) return;
        String hora = String.format("%02d:%02d", horaInicio.getValue(), minutoInicio.getValue());
        lblHoraSimulada.setText("Hora inicial: " + hora);
        lblRelojStatus.setText("Hora: " + hora);
    }

    private void actualizarResumenPasajeros() {
        int escala = escalaDemanda.getValue() == null ? 1 : escalaDemanda.getValue();
        long efectiva = (long) pasajerosSimulados.getValue() * escala;
        resumenPasajeros.setText("Demanda base por ruta: " + pasajerosSimulados.getValue()
                + " pasajeros" + (escala > 1 ? " x" + escala + " = " + efectiva + " al simular" : "")
                + "; ajustada por la hora del día. La escala se aplica al iniciar la simulación.");
    }

    // ==================================================================
    //  RUTAS DE MICROS
    // ==================================================================

    private void crearRutaMicro() {
        editarRutaMicro(null);
    }

    private void editarRutaMicro(EberthMServicioMicro existente) {
        if (grafo == null || puntos.size() < 2) {
            lblEstado.setText("Carga al menos dos paraderos antes de definir una ruta.");
            return;
        }
        Optional<LuisYDialogoRutaMicro.Datos> datos = LuisYDialogoRutaMicro.mostrar(
                this::estilizarDialogo, this::mostrarError, puntos, rutasMicros, existente,
                pasajerosSimulados.getValue());
        if (datos.isEmpty()) return;
        LuisYDialogoRutaMicro.Datos d = datos.get();

        String rutaNombre = d.nombre();
        List<LuisYPuntoImportante> seleccionados = d.paraderos();
        if (rutaNombre.isBlank() || seleccionados.size() < 2) {
            mostrarError("Ruta incompleta",
                    "Indica un nombre y selecciona al menos dos paraderos.");
            return;
        }
        EberthMTipoRetorno retorno = d.retorno();
        List<LuisYPuntoImportante> vueltaManual = d.vueltaManual();
        if (retorno == EberthMTipoRetorno.MANUAL && vueltaManual.size() < 2) {
            mostrarError("Vuelta incompleta",
                    "Selecciona al menos dos paraderos de vuelta o elige otro tipo de retorno.");
            return;
        }
        String id = existente == null ? "micro-" + UUID.randomUUID() : existente.id();
        String colorHex = d.colorHex();
        if (EberthMGestorServicios.colorEnUso(rutasMicros, id, colorHex)) {
            mostrarError("Color repetido", "Cada ruta necesita un color distinto.");
            return;
        }
        int frecuenciaMin = d.frecuenciaMin();
        int unidadesValor = d.unidades();
        int pasajerosValor = d.pasajeros();
        boolean optimizar = d.optimizarOrden();
        PieroGGrafoVial red = grafo;
        // Dijkstra/A* fuera del hilo de JavaFX: en Lima puede tardar segundos.
        enSegundoPlano("Calculando ruta " + rutaNombre, () -> {
            return EberthMGestorServicios.construirServicio(red, id, rutaNombre, seleccionados,
                    optimizar, retorno, vueltaManual, frecuenciaMin, unidadesValor,
                    pasajerosValor, colorHex);
        }, servicio -> {
            if (EberthMGestorServicios.flotaExcedida(rutasMicros, servicio)) {
                mostrarError("Capacidad de flota excedida",
                        "La suma de unidades de todas las rutas no puede superar 6.000.");
                return;
            }
            int idx = EberthMGestorServicios.indiceDe(rutasMicros, id);
            if (idx >= 0) {
                rutasMicros.set(idx, servicio);
            } else {
                rutasMicros.add(servicio);
                rutasMicrosVisibles.add(id);
            }
            gestor.agregar(servicio.trayecto());
            gestor.eliminar(id + "-vuelta");
            actualizarFilasRutasMicro();
            actualizarVisualizacionRutas();
            guardarRutasMicros();
            lblEstado.setText(rutaNombre + ": " + seleccionados.size() + " paraderos, cada "
                    + frecuenciaMin + " min · " + servicio.trayecto().resumen());
        });
    }

    private void eliminarRutaMicro(EberthMServicioMicro ruta) {
        if (!confirmar("Eliminar ruta", "¿Eliminar la ruta «" + ruta.nombre() + "»?")) return;
        rutasMicros.removeIf(r -> r.id().equals(ruta.id()));
        rutasMicrosVisibles.remove(ruta.id());
        gestor.eliminar(ruta.id());
        actualizarFilasRutasMicro();
        actualizarVisualizacionRutas();
        guardarRutasMicros();
        lblEstado.setText("Ruta eliminada: " + ruta.nombre());
    }

    private void marcarTodasLasMicros(boolean visibles) {
        rutasMicrosVisibles.clear();
        if (visibles) rutasMicros.forEach(r -> rutasMicrosVisibles.add(r.id()));
        actualizarFilasRutasMicro();
        actualizarVisualizacionRutas();
    }

    /** Filas con checkbox (vista múltiple), muestra de color y acciones. */
    private void actualizarFilasRutasMicro() {
        filasRutasMicros.getChildren().clear();
        EberthMServicioMicro seleccion = selectorMicro.getValue();
        String idSeleccion = seleccion == null ? null : seleccion.id();
        ignorarSeleccionMicro(() -> {
            selectorMicro.getItems().setAll(rutasMicros);
            selectorMicro.setValue(idSeleccion == null ? null : rutasMicros.stream()
                    .filter(r -> r.id().equals(idSeleccion)).findFirst().orElse(null));
        });
        if (rutasMicros.isEmpty()) {
            filasRutasMicros.getChildren().add(ayuda("No hay rutas de micro definidas. "
                    + "Crea una con «Crear ruta»."));
            return;
        }
        for (EberthMServicioMicro ruta : rutasMicros) {
            CheckBox visible = new CheckBox();
            visible.setSelected(rutasMicrosVisibles.contains(ruta.id()));
            visible.setTooltip(new Tooltip("Mostrar en la vista múltiple"));
            visible.setOnAction(e -> {
                if (visible.isSelected()) rutasMicrosVisibles.add(ruta.id());
                else rutasMicrosVisibles.remove(ruta.id());
                actualizarVisualizacionRutas();
            });
            Region muestra = new Region();
            muestra.getStyleClass().add("muestra-color");
            muestra.setStyle("-fx-background-color: " + ruta.color() + ";");
            Label nombre = new Label(ruta.nombre());
            nombre.getStyleClass().add("micro-nombre");
            Label detalle = new Label(String.format(Locale.ROOT,
                    "%d min · %d micros · ida %.1f km%s",
                    ruta.frecuenciaMinutos(), ruta.unidadesDisponibles(),
                    ruta.trayecto().distanciaMetros() / 1000,
                    ruta.tieneRetorno() ? String.format(Locale.ROOT, " · vuelta %.1f km",
                            ruta.trayectoVuelta().distanciaMetros() / 1000) : " · sin retorno"));
            detalle.getStyleClass().add("texto-secundario");
            Label recorrido = new Label(ruta.trayecto().puntos().stream()
                    .map(LuisYPuntoImportante::nombre).collect(Collectors.joining(" → ")));
            recorrido.setWrapText(true);
            recorrido.getStyleClass().addAll("texto-secundario", "micro-recorrido");

            Button idaVuelta = botonCompacto("⇄", () -> {
                selectorMicro.setValue(ruta);
                btnModoIdaVuelta.setSelected(true);
                alternarModoIdaVuelta();
            });
            idaVuelta.setTooltip(new Tooltip("Ver solo su ida (azul) y vuelta (roja)"));
            Button editar = botonCompacto("✎", () -> editarRutaMicro(ruta));
            editar.setTooltip(new Tooltip("Editar ruta"));
            Button eliminar = botonCompacto("✕", () -> eliminarRutaMicro(ruta));
            eliminar.setTooltip(new Tooltip("Eliminar ruta"));

            HBox cabecera = new HBox(8, visible, muestra, nombre);
            cabecera.setAlignment(Pos.CENTER_LEFT);
            VBox info = new VBox(3, cabecera, detalle, recorrido);
            HBox fila = new HBox(6, info, idaVuelta, editar, eliminar);
            fila.setAlignment(Pos.CENTER_LEFT);
            fila.getStyleClass().add("fila-micro");
            if (ruta.id().equals(idSeleccion) && btnModoIdaVuelta.isSelected()) {
                fila.getStyleClass().add("fila-micro-activa");
            }
            HBox.setHgrow(info, Priority.ALWAYS);
            filasRutasMicros.getChildren().add(fila);
        }
    }

    private boolean actualizandoSelectorMicro;

    private void ignorarSeleccionMicro(Runnable accion) {
        actualizandoSelectorMicro = true;
        try {
            accion.run();
        } finally {
            actualizandoSelectorMicro = false;
        }
    }

    private void cargarRutasMicrosGuardadas() {
        List<BetoHRepositorioServiciosMicro.RutaGuardada> guardadas;
        try {
            guardadas = repositorioMicros.cargar();
        } catch (IOException ex) {
            lblEstado.setText("No se pudieron cargar rutas guardadas: " + ex.getMessage());
            return;
        }
        if (guardadas.isEmpty()) return;
        PieroGGrafoVial red = grafo;
        enSegundoPlano("Restaurando " + guardadas.size() + " rutas de " + ciudadActual.nombre(), () -> {
            return EberthMGestorServicios.restaurar(red, guardadas);
        }, restauradas -> {
            boolean repararColores = false;
            for (EberthMServicioMicro servicio : restauradas) {
                String colorGuardado = colorHex(Color.web(servicio.color()));
                EberthMServicioMicro ajustado = servicio;
                if (rutasMicros.stream().anyMatch(r -> r.color().equalsIgnoreCase(colorGuardado))) {
                    ajustado = new EberthMServicioMicro(servicio.id(), servicio.nombre(), servicio.trayecto(),
                            servicio.trayectoVuelta(), servicio.frecuenciaMinutos(),
                            servicio.unidadesDisponibles(), servicio.pasajerosEsperando(),
                            EberthMGestorServicios.siguienteColor(rutasMicros), servicio.retorno());
                    repararColores = true;
                }
                rutasMicros.add(ajustado);
                rutasMicrosVisibles.add(ajustado.id());
                gestor.agregar(ajustado.trayecto());
            }
            actualizarFilasRutasMicro();
            actualizarVisualizacionRutas();
            if (repararColores) guardarRutasMicros();
            lblEstado.setText("Rutas de micros restauradas: " + rutasMicros.size());
        });
    }

    private void guardarRutasMicros() {
        try {
            repositorioMicros.guardar(rutasMicros);
        } catch (IOException ex) {
            lblEstado.setText("No se pudieron guardar las rutas: " + ex.getMessage());
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Error al guardar rutas", t);
        }
    }

    // ==================================================================
    //  VISTAS DE RUTAS (múltiple / ida-vuelta)
    // ==================================================================

    private EberthMServicioMicro microSeleccionada() {
        EberthMServicioMicro valor = selectorMicro.getValue();
        if (valor == null) return null;
        int idx = EberthMGestorServicios.indiceDe(rutasMicros, valor.id());
        return idx >= 0 ? rutasMicros.get(idx) : null;
    }

    private void alternarModoIdaVuelta() {
        if (btnModoIdaVuelta.isSelected() && microSeleccionada() == null) {
            List<EberthMServicioMicro> visibles = rutasMicros.stream()
                    .filter(r -> rutasMicrosVisibles.contains(r.id())).toList();
            if (visibles.size() == 1) {
                selectorMicro.setValue(visibles.getFirst());
            } else {
                btnModoIdaVuelta.setSelected(false);
                lblEstado.setText("Elige primero una micro para ver su ida y vuelta.");
                return;
            }
        }
        actualizarFilasRutasMicro();
        actualizarVisualizacionRutas();
        EberthMServicioMicro micro = microSeleccionada();
        if (btnModoIdaVuelta.isSelected() && micro != null) {
            mapa.enfocarTrayectos(trayectosVisibles(micro));
            lblEstado.setText("Modo Ida/Vuelta: " + micro.nombre()
                    + " · ida azul, vuelta roja. Las demás rutas se ocultan.");
        } else {
            lblEstado.setText("Vista múltiple: " + rutasMicrosVisibles.size() + " rutas marcadas.");
        }
    }

    private void actualizarVisualizacionRutas() {
        if (actualizandoSelectorMicro) return;
        EberthMServicioMicro micro = microSeleccionada();
        boolean modoIdaVuelta = btnModoIdaVuelta.isSelected() && micro != null;
        mapa.setRutasMicros(rutasMicros, rutasMicrosVisibles);
        mapa.setMicroIdaVuelta(modoIdaVuelta ? micro : null);
        mapa.setSentidosVisibles(chkIda.isSelected(), chkVuelta.isSelected());
        mapa.setTrayectoPartida(trayectoPartida);
        mapa.setTrayectoRetorno(trayectoRetorno);
        if (modoIdaVuelta) {
            panelParadas.mostrar(micro);
            return;
        }
        List<EberthMServicioMicro> visibles = rutasMicros.stream()
                .filter(ruta -> rutasMicrosVisibles.contains(ruta.id())).toList();
        if (visibles.size() == 1) panelParadas.mostrar(visibles.getFirst());
        else panelParadas.ocultar();
    }

    private static List<LuisYTrayecto> trayectosVisibles(EberthMServicioMicro micro) {
        return micro.tieneRetorno() ? List.of(micro.trayecto(), micro.trayectoVuelta())
                : List.of(micro.trayecto());
    }

    private void enfocarSeleccion() {
        EberthMServicioMicro micro = microSeleccionada();
        if (micro != null) {
            mapa.enfocarTrayectos(trayectosVisibles(micro));
            return;
        }
        List<LuisYTrayecto> visibles = new ArrayList<>();
        for (EberthMServicioMicro r : rutasMicros) {
            if (!rutasMicrosVisibles.contains(r.id())) continue;
            visibles.add(r.trayecto());
            if (r.tieneRetorno()) visibles.add(r.trayectoVuelta());
        }
        if (trayectoPartida != null) visibles.add(trayectoPartida);
        if (trayectoRetorno != null) visibles.add(trayectoRetorno);
        if (visibles.isEmpty()) {
            lblEstado.setText("No hay rutas visibles para enfocar.");
            return;
        }
        mapa.enfocarTrayectos(visibles);
    }

    // ==================================================================
    //  PUNTOS
    // ==================================================================

    private void procesarClicMapa(double lon, double lat) {
        try {
            if (colocandoPunto) {
                colocandoPunto = false;
                editarPunto(null, new double[]{lon, lat});
            } else if (colocandoEvento) {
                colocandoEvento = false;
                agregarEvento(lon, lat);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Error al procesar clic de mapa", t);
        }
    }

    private void editarPunto(LuisYPuntoImportante existente, double[] coordenadas) {
        Dialog<LuisYPuntoImportante> dialogo = new Dialog<>();
        estilizarDialogo(dialogo);
        dialogo.setTitle(existente == null ? "Nueva ubicación" : "Editar ubicación");
        dialogo.setHeaderText("Nombre y coordenadas (longitud, latitud)");
        ButtonType aceptar = new ButtonType("Guardar", ButtonBar.ButtonData.OK_DONE);
        dialogo.getDialogPane().getButtonTypes().addAll(aceptar, ButtonType.CANCEL);

        double[] centro = BetoHServicioPuntos.centroPorDefecto(grafo);
        TextField id = new TextField(existente == null ? BetoHServicioPuntos.nuevoId(puntos) : existente.id());
        TextField nombre = new TextField(existente == null ? "" : existente.nombre());
        TextField lon = new TextField(String.valueOf(existente != null ? existente.lon()
                : coordenadas == null ? centro[0] : coordenadas[0]));
        TextField lat = new TextField(String.valueOf(existente != null ? existente.lat()
                : coordenadas == null ? centro[1] : coordenadas[1]));
        ComboBox<LuisYPuntoImportante.TipoPunto> tipo = new ComboBox<>();
        tipo.getItems().setAll(LuisYPuntoImportante.TipoPunto.values());
        tipo.getSelectionModel().select(existente == null
                ? LuisYPuntoImportante.TipoPunto.PARADA : existente.tipo());

        GridPane campos = new GridPane();
        campos.setHgap(8);
        campos.setVgap(8);
        campos.addRow(0, new Label("ID"), id);
        campos.addRow(1, new Label("Nombre"), nombre);
        campos.addRow(2, new Label("Longitud"), lon);
        campos.addRow(3, new Label("Latitud"), lat);
        campos.addRow(4, new Label("Tipo"), tipo);
        dialogo.getDialogPane().setContent(campos);
        dialogo.setResultConverter(boton -> {
            if (boton != aceptar) return null;
            try {
                return new LuisYPuntoImportante(id.getText().trim(), nombre.getText().trim(),
                        Double.parseDouble(lon.getText().trim().replace(',', '.')),
                        Double.parseDouble(lat.getText().trim().replace(',', '.')), tipo.getValue());
            } catch (RuntimeException ex) {
                lblEstado.setText("Ubicación inválida: " + ex.getMessage());
                return null;
            }
        });

        Optional<LuisYPuntoImportante> resultado = dialogo.showAndWait();
        resultado.ifPresent(punto -> {
            boolean idDuplicado = puntos.stream().anyMatch(actual ->
                    actual.id().equals(punto.id()) && actual != existente);
            if (idDuplicado) {
                mostrarError("ID duplicado", "Cada ubicación debe tener un ID único.");
                return;
            }
            if (existente != null) puntos.remove(existente);
            puntos.add(punto);
            puntosModificados = true;
            actualizarListaPuntos();
            lblEstado.setText("Ubicación actualizada. Guarda el JSON para conservar.");
        });
    }

    /** Centro del mapa actual (para nuevas ubicaciones sin coordenadas). */
    private void eliminarPunto() {
        LuisYPuntoImportante punto = listaPuntos.getSelectionModel().getSelectedItem();
        if (punto == null) {
            lblEstado.setText("Selecciona una ubicación para eliminar.");
            return;
        }
        puntos.remove(punto);
        puntosModificados = true;
        actualizarListaPuntos();
        lblEstado.setText("Ubicación eliminada. Guarda el JSON para conservar cambios.");
    }

    private void actualizarListaPuntos() {
        puntosObservables.setAll(puntos);
        mapa.setPuntos(puntos);
        regenerarSemaforos();
    }

    /** Hospitales, colegios y mercados cargados deciden dónde hay semáforos. */
    private void regenerarSemaforos() {
        PieroGGrafoVial red = grafo;
        if (red == null) return;
        List<double[]> criticos = puntos.stream()
                .filter(p -> p.tipo() == LuisYPuntoImportante.TipoPunto.SALUD
                        || p.tipo() == LuisYPuntoImportante.TipoPunto.EDUCACION
                        || p.tipo() == LuisYPuntoImportante.TipoPunto.MERCADO)
                .map(p -> new double[]{p.lon(), p.lat()}).toList();
        motor.ejecutar(s -> red.regenerarSemaforos(criticos));
    }

    /** Lee {@code mapas/<ciudad>/puntos.json}: marcadores encima de las calles. */
    private void cargarPuntosCiudad() {
        puntos.clear();
        if (!fuenteMapa.existe(ciudadActual, BetoHFuenteMapa.PUNTOS)) {
            actualizarListaPuntos();
            lblEstado.setText(ciudadActual.nombre() + " no tiene puntos.json; agrega ubicaciones "
                    + "desde el mapa o importa un JSON.");
            return;
        }
        try (InputStream entrada = fuenteMapa.abrir(ciudadActual, BetoHFuenteMapa.PUNTOS)) {
            puntos.addAll(lectorJson.leer(entrada));
            puntosModificados = false;
            actualizarListaPuntos();
            lblEstado.setText("Red de " + ciudadActual.nombre() + " lista. "
                    + puntos.size() + " puntos de interés cargados.");
        } catch (IOException | RuntimeException ex) {
            actualizarListaPuntos();
            lblEstado.setText("No se pudieron cargar los puntos: " + ex.getMessage());
        }
    }

    @FunctionalInterface
    private interface LectorPuntos {
        List<LuisYPuntoImportante> leer(java.nio.file.Path archivo) throws IOException;
    }

    private void cargarPuntosJson() {
        importarPuntos("Importar ubicaciones JSON", "JSON", "*.json", lectorJson::leer);
    }

    private void cargarPuntosExcel() {
        importarPuntos("Importar ubicaciones Excel", "Excel", "*.xlsx", lectorExcel::leer);
    }

    /** Único camino de importación de ubicaciones desde archivo; reemplaza la lista actual. */
    private void importarPuntos(String titulo, String descripcion, String patron, LectorPuntos lector) {
        File archivo = elegirArchivo(titulo, descripcion, patron, false, null);
        if (archivo == null) return;
        try {
            List<LuisYPuntoImportante> nuevos = lector.leer(archivo.toPath());
            puntos.clear();
            puntos.addAll(nuevos);
            puntosModificados = true;
            actualizarListaPuntos();
            lblEstado.setText("Ubicaciones importadas: " + puntos.size());
        } catch (IOException | RuntimeException ex) {
            lblEstado.setText("Error al importar ubicaciones: " + ex.getMessage());
        }
    }

    /**
     * Botón «Extraer Puntos»: delega en {@link DanielTImportadorMapa}, que lee el
     * {@code calles.geojson} de la ciudad seleccionada y sobreescribe su {@code puntos.json}.
     * Después se relee el archivo para ver los puntos sin reiniciar la app.
     */
    private void cargarPuntosGeoJson() {
        PieroGCiudadMapa ciudad = ciudadActual;
        if (puntosModificados && !confirmar("Extraer puntos",
                "Hay ubicaciones sin guardar en " + ciudad.nombre()
                        + ". Se sobreescribirá su puntos.json. ¿Continuar?")) {
            return;
        }
        enSegundoPlano("Extrayendo puntos de interés de las calles de " + ciudad.nombre(), () -> {
            try {
                return importadorMapa.extraerDeCalles(ciudad);
            } catch (IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }, resultado -> {
            if (resultado.encontrados() == 0) {
                Alert alerta = new Alert(Alert.AlertType.INFORMATION,
                        "El calles.geojson de " + ciudad.nombre() + " solo contiene calles. "
                                + "No se encontraron puntos de interés; puntos.json no se modificó.",
                        ButtonType.OK);
                estilizarDialogo(alerta);
                alerta.setTitle("Sin puntos de interés");
                alerta.setHeaderText(null);
                alerta.showAndWait();
                lblEstado.setText("Extracción sin resultados: no hay puntos de interés en las calles.");
                return;
            }
            cargarPuntosCiudad();
            lblEstado.setText(resultado.encontrados() + " puntos de interés extraídos de "
                    + ciudad.nombre() + " (guardados en " + resultado.guardado() + ").");
        });
    }

    private void guardarPuntos() {
        File archivo = elegirArchivo("Guardar ubicaciones JSON", "JSON", "*.json", true, "puntos.json");
        if (archivo == null) return;
        try {
            lectorJson.guardar(puntos, archivo.toPath());
            puntosModificados = false;
            lblEstado.setText("Ubicaciones guardadas: " + archivo.getAbsolutePath()
                    + ". Para usarlas al abrir la ciudad, cópialas a "
                    + ciudadActual.carpeta() + "/puntos.json");
        } catch (IOException ex) {
            lblEstado.setText("Error al guardar ubicaciones: " + ex.getMessage());
        }
    }

    private void crearPlantillaExcel() {
        File archivo = elegirArchivo("Guardar plantilla Excel", "Excel", "*.xlsx", true,
                "plantilla-puntos.xlsx");
        if (archivo == null) return;
        try (var libro = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var hoja = libro.createSheet("Puntos");
            var cabecera = hoja.createRow(0);
            String[] columnas = {"id", "nombre", "lon", "lat", "tipo"};
            for (int i = 0; i < columnas.length; i++) {
                cabecera.createCell(i).setCellValue(columnas[i]);
            }
            try (var salida = java.nio.file.Files.newOutputStream(archivo.toPath())) {
                libro.write(salida);
            }
            lblEstado.setText("Plantilla creada: " + archivo.getAbsolutePath());
        } catch (IOException ex) {
            lblEstado.setText("Error al crear plantilla: " + ex.getMessage());
        }
    }

    private File elegirArchivo(String titulo, String descripcion, String patron, boolean guardar,
                               String nombreInicial) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(titulo);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(descripcion, patron));
        if (nombreInicial != null) chooser.setInitialFileName(nombreInicial);
        var ventana = root.getScene() == null ? null : root.getScene().getWindow();
        return guardar ? chooser.showSaveDialog(ventana) : chooser.showOpenDialog(ventana);
    }

    // ==================================================================
    //  TRAYECTOS CON TODOS LOS PUNTOS
    // ==================================================================

    private void calcular(String sentido) {
        if (grafo == null) {
            lblEstado.setText("La red vial todavía no está lista.");
            return;
        }
        if (puntos.size() < 2) {
            lblEstado.setText("Se necesitan al menos 2 ubicaciones.");
            return;
        }
        List<LuisYPuntoImportante> orden = new ArrayList<>(puntos);
        PieroGGrafoVial red = grafo;
        enSegundoPlano("Calculando trayecto " + sentido, () -> {
            return EberthMGestorServicios.calcularTrayecto(red, sentido, orden);
        }, trayecto -> {
            gestor.agregar(trayecto);
            if ("PARTIDA".equals(sentido)) trayectoPartida = trayecto;
            else trayectoRetorno = trayecto;
            actualizarVisualizacionRutas();
            listaTrayectos.getItems().add(sentido + " · " + trayecto.resumen());
            lblEstado.setText("Trayecto " + sentido + ": " + trayecto.resumen());
        });
    }

    private void limpiarTrayectos() {
        gestor.limpiar();
        rutasMicros.forEach(r -> gestor.agregar(r.trayecto()));
        listaTrayectos.getItems().clear();
        trayectoPartida = null;
        trayectoRetorno = null;
        actualizarVisualizacionRutas();
    }

    // ==================================================================
    //  EXPORTACIÓN
    // ==================================================================

    private static DanielTRutaExportable exportableIda(EberthMServicioMicro s) {
        return new DanielTRutaExportable(s.nombre() + " · Ida", s.color(), s.trayecto());
    }

    /** Ruta de vuelta exportable, o null si la micro no tiene retorno. */
    private static DanielTRutaExportable exportableVuelta(EberthMServicioMicro s) {
        if (!s.tieneRetorno()) return null;
        return new DanielTRutaExportable(s.nombre() + " · Vuelta", s.color(), s.trayectoVuelta());
    }

    private Path elegirDestinoPdf(String titulo, String nombreInicial) {
        File archivo = elegirArchivo(titulo, "PDF", "*.pdf", true, nombreInicial);
        if (archivo == null) return null;
        return asegurarExtension(archivo.toPath(), "pdf");
    }

    private static Path asegurarExtension(Path destino, String extension) {
        if (destino.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("." + extension)) {
            return destino;
        }
        return destino.resolveSibling(destino.getFileName() + "." + extension);
    }

    private DanielTExportadorPdf exportadorPdf() {
        DanielTExportadorPdf exportador = new DanielTExportadorPdf();
        exportador.setNombreCiudad(ciudadActual.nombre().toUpperCase(Locale.ROOT));
        return exportador;
    }

    /** Vista múltiple: todas las micros marcadas en los checkboxes, en una lámina. */
    private void exportarPdfMultiple() {
        List<DanielTRutaExportable> rutas = new ArrayList<>();
        for (EberthMServicioMicro s : rutasMicros) {
            if (!rutasMicrosVisibles.contains(s.id())) continue;
            rutas.add(exportableIda(s));
            if (s.tieneRetorno()) rutas.add(exportableVuelta(s));
        }
        if (rutas.isEmpty()) {
            if (trayectoPartida != null) {
                rutas.add(new DanielTRutaExportable("Trayecto · Ida", LuisYPaletaColores.COLOR_RUTA_IDA,
                        trayectoPartida));
            }
            if (trayectoRetorno != null) {
                rutas.add(new DanielTRutaExportable("Trayecto · Vuelta", LuisYPaletaColores.COLOR_RUTA_VUELTA,
                        trayectoRetorno));
            }
        }
        if (rutas.isEmpty()) {
            mostrarError("Nada que exportar", "Marca al menos una micro en «Rutas y Puntos».");
            return;
        }
        Path destino = elegirDestinoPdf("Exportar vista múltiple",
                "rutas-" + ciudadActual.id() + ".pdf");
        if (destino == null) return;
        String titulo = "Rutas de " + ciudadActual.nombre();
        DanielTExportadorPdf exportador = exportadorPdf();
        enSegundoPlano("Exportando PDF múltiple", () -> {
            try {
                exportador.exportarVistaMultiple(rutas, titulo, destino);
                return destino;
            } catch (IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }, ruta -> lblEstado.setText("PDF de vista múltiple exportado: " + ruta));
    }

    /** Vista individual: solo la ida/vuelta de la micro seleccionada. */
    private void exportarPdfIndividual() {
        EberthMServicioMicro micro = microSeleccionada();
        if (micro == null) {
            List<EberthMServicioMicro> visibles = rutasMicros.stream()
                    .filter(r -> rutasMicrosVisibles.contains(r.id())).toList();
            if (visibles.size() == 1) micro = visibles.getFirst();
        }
        if (micro == null) {
            mostrarError("Elige una micro", "Selecciona la micro en «Vista Ida / Vuelta» "
                    + "(o deja marcada solo una) para exportar su vista individual.");
            return;
        }
        Path destino = elegirDestinoPdf("Exportar vista individual",
                micro.nombre().replaceAll("[^A-Za-z0-9_-]+", "-") + ".pdf");
        if (destino == null) return;
        EberthMServicioMicro elegida = micro;
        DanielTExportadorPdf exportador = exportadorPdf();
        enSegundoPlano("Exportando PDF de " + micro.nombre(), () -> {
            try {
                exportador.exportarVistaIndividual(exportableIda(elegida),
                        exportableVuelta(elegida), destino);
                return destino;
            } catch (IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }, ruta -> lblEstado.setText("PDF individual exportado: " + ruta));
    }

    private void exportarRutas() {
        List<OpcionExportacion> opciones = opcionesExportables();
        if (opciones.isEmpty()) {
            lblEstado.setText("No hay rutas calculadas o servicios para exportar.");
            return;
        }
        Dialog<ButtonType> dialogo = new Dialog<>();
        estilizarDialogo(dialogo);
        dialogo.setTitle("Exportar rutas");
        dialogo.setHeaderText("Elige las rutas y sentidos que quieres incluir.");
        dialogo.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ListView<OpcionExportacion> lista = new ListView<>(
                FXCollections.observableArrayList(opciones));
        lista.setCellFactory(view -> new ListCell<>() {
            private final CheckBox check = new CheckBox();
            {
                check.setMaxWidth(Double.MAX_VALUE);
                check.setOnAction(event -> {
                    OpcionExportacion opcion = getItem();
                    if (opcion != null) opcion.seleccionada = check.isSelected();
                });
            }

            @Override
            protected void updateItem(OpcionExportacion opcion, boolean vacio) {
                super.updateItem(opcion, vacio);
                if (vacio || opcion == null) {
                    setGraphic(null);
                } else {
                    check.setText(opcion.ruta.nombre());
                    check.setSelected(opcion.seleccionada);
                    check.setTextFill(Color.web(opcion.ruta.color()));
                    setGraphic(check);
                }
            }
        });
        lista.setPrefHeight(320);
        lista.setPrefWidth(430);

        ComboBox<String> formato = new ComboBox<>(FXCollections.observableArrayList(
                "PDF (una lámina por micro)", "GeoJSON", "GPX", "CSV", "TXT"));
        formato.getSelectionModel().selectFirst();
        Button seleccionarVisibles = new Button("Marcar visibles");
        seleccionarVisibles.setOnAction(event -> {
            opciones.forEach(opcion -> opcion.seleccionada = opcion.visible);
            lista.refresh();
        });
        Button seleccionarTodas = new Button("Marcar todas");
        seleccionarTodas.setOnAction(event -> {
            opciones.forEach(opcion -> opcion.seleccionada = true);
            lista.refresh();
        });
        Button limpiarSeleccion = new Button("Desmarcar");
        limpiarSeleccion.setOnAction(event -> {
            opciones.forEach(opcion -> opcion.seleccionada = false);
            lista.refresh();
        });
        HBox accionesLista = new HBox(6, seleccionarVisibles, seleccionarTodas, limpiarSeleccion);
        VBox contenido = new VBox(10,
                new Label("Rutas y sentidos"), lista, accionesLista,
                new Label("Formato"), formato);
        contenido.setPadding(new Insets(4));
        dialogo.getDialogPane().setContent(contenido);
        dialogo.getDialogPane().setPrefWidth(520);
        Optional<ButtonType> resultado = dialogo.showAndWait();
        if (resultado.isEmpty() || resultado.get() != ButtonType.OK) return;

        List<DanielTRutaExportable> seleccionadas = opciones.stream()
                .filter(opcion -> opcion.seleccionada)
                .map(opcion -> opcion.ruta).toList();
        if (seleccionadas.isEmpty()) {
            lblEstado.setText("Marca al menos una ruta para exportar.");
            return;
        }
        String elegido = formato.getValue();
        String extension = switch (elegido) {
            case "GeoJSON" -> "geojson";
            case "GPX" -> "gpx";
            case "CSV" -> "csv";
            case "TXT" -> "txt";
            default -> "pdf";
        };
        File archivo = elegirArchivo("Exportar " + seleccionadas.size() + " rutas",
                extension.toUpperCase(Locale.ROOT), "*." + extension, true,
                "rutas-seleccionadas." + extension);
        if (archivo == null) return;
        Path destino = asegurarExtension(archivo.toPath(), extension);
        DanielTExportadorPdf exportador = exportadorPdf();
        enSegundoPlano("Exportando " + seleccionadas.size() + " rutas", () -> {
            try {
                switch (extension) {
                    case "pdf" -> exportador.exportar(seleccionadas, destino);
                    case "geojson" -> expGeoJson.exportar(seleccionadas, destino);
                    case "gpx" -> expGpx.exportar(seleccionadas, destino);
                    case "csv" -> expCsv.exportar(seleccionadas, destino);
                    case "txt" -> new DanielTExportadorTxt().exportar(seleccionadas, destino);
                    default -> throw new IllegalArgumentException("Formato desconocido: " + extension);
                }
                return destino;
            } catch (IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }, ruta -> lblEstado.setText("Exportadas " + seleccionadas.size() + " rutas: " + ruta));
    }

    private List<OpcionExportacion> opcionesExportables() {
        List<OpcionExportacion> opciones = new ArrayList<>();
        for (EberthMServicioMicro servicio : rutasMicros) {
            boolean visible = rutasMicrosVisibles.contains(servicio.id());
            opciones.add(new OpcionExportacion(exportableIda(servicio), visible, visible));
            if (servicio.tieneRetorno()) {
                opciones.add(new OpcionExportacion(exportableVuelta(servicio), visible, visible));
            }
        }
        if (trayectoPartida != null) {
            opciones.add(new OpcionExportacion(new DanielTRutaExportable(trayectoPartida.nombre(),
                    LuisYPaletaColores.COLOR_RUTA_IDA, trayectoPartida), true, chkIda.isSelected()));
        }
        if (trayectoRetorno != null) {
            opciones.add(new OpcionExportacion(new DanielTRutaExportable(trayectoRetorno.nombre(),
                    LuisYPaletaColores.COLOR_RUTA_VUELTA, trayectoRetorno), true, chkVuelta.isSelected()));
        }
        return opciones;
    }

    // ==================================================================
    //  EVENTOS
    // ==================================================================

    /** Tipo de evento local armado en el grid, o {@code null} si no hay ninguno. */
    private EberthMTipoEvento tipoEventoSeleccionado() {
        Toggle t = grupoEventosLocales.getSelectedToggle();
        return t == null ? null : (EberthMTipoEvento) t.getUserData();
    }

    private void agregarEvento(double lon, double lat) {
        EberthMTipoEvento tipo = tipoEventoSeleccionado();
        if (tipo == null) return;
        grupoEventosLocales.selectToggle(null);
        publicarEvento(tipo, lon, lat);
    }

    private void publicarEvento(EberthMTipoEvento tipo, double lon, double lat) {
        String nombre = nombreEvento.getText().isBlank()
                ? tipo + " " + (eventos.size() + 1) : nombreEvento.getText().trim();
        List<EberthMEventoTrafico> nuevos = new ArrayList<>(eventos);
        nuevos.add(new EberthMEventoTrafico(nombre, tipo, lon, lat));
        gestorEventos.publicar(nuevos);
        lblEstado.setText("Evento agregado: " + nombre
                + ". Las rutas se recalculan sin detener la simulación.");
    }

    private void alternarHoraPunta(boolean activa) {
        motor.calcular(s -> {
            s.setHoraPuntaForzada(activa);
            return null;
        });
        lblEstado.setText(activa ? "Hora punta forzada en toda la ciudad."
                : "Hora punta según el reloj de la simulación.");
    }

    private void eliminarEvento() {
        EberthMEventoTrafico evento = listaEventos.getSelectionModel().getSelectedItem();
        if (evento == null) {
            lblEstado.setText("Selecciona un evento para eliminar.");
            return;
        }
        List<EberthMEventoTrafico> nuevos = new ArrayList<>(eventos);
        nuevos.remove(evento);
        gestorEventos.publicar(nuevos);
        lblEstado.setText("Evento eliminado; costos recalculándose.");
    }

    private void eliminarTodosLosEventos() {
        if (eventos.isEmpty()) return;
        gestorEventos.publicar(List.of());
        lblEstado.setText("Eventos eliminados; costos recalculándose.");
    }

    private record ResultadoEventos(EberthMAplicadorEventos.Resultado tramos,
                                    List<EberthMServicioMicro> servicios) {}

    /**
     * Listener del gestor de eventos (hilo de JavaFX). La parte visible se
     * actualiza al instante; aplicar costos al grafo y recalcular desvíos se
     * hace en el hilo del motor, sin congelar la interfaz.
     */
    private void procesarEventosActualizados(List<EberthMEventoTrafico> nuevosEventos) {
        eventos.clear();
        eventos.addAll(nuevosEventos);
        listaEventos.getItems().setAll(eventos);
        mapa.setEventos(eventos);
        if (grafo == null) return;
        PieroGGrafoVial red = grafo;
        List<EberthMEventoTrafico> copiaEventos = List.copyOf(eventos);
        List<EberthMServicioMicro> copiaServicios = List.copyOf(rutasMicros);
        long version = ++versionEventos;
        long generacion = generacionCiudad;
        trabajoIniciado();
        motor.calcular(s -> {
            var tramos = EberthMAplicadorEventos.aplicar(red, copiaEventos);
            s.actualizarEventos(copiaEventos);
            List<EberthMServicioMicro> recalculadas = EberthMGestorServicios.recalcular(red, copiaServicios);
            s.actualizarServicios(recalculadas);
            return new ResultadoEventos(tramos, recalculadas);
        }).whenComplete((resultado, error) -> Platform.runLater(() -> {
            trabajoTerminado();
            if (generacion != generacionCiudad || version != versionEventos) return;
            if (error != null) {
                LOG.log(Level.SEVERE, "Error al aplicar eventos", error);
                lblEstado.setText("No se pudieron aplicar los eventos: " + error.getMessage());
                return;
            }
            mapa.setViasAfectadas(resultado.tramos().cerradas(), resultado.tramos().trancaderas());
            aplicarServiciosRecalculados(copiaServicios, resultado.servicios());
            if (motor.instantanea().ejecutando()) {
                lblEstado.setText("Eventos aplicados; simulación activa y rutas recalculadas.");
            } else {
                lblEstado.setText("Eventos aplicados: " + resultado.tramos().cerradas().size()
                        + " tramos cerrados; rutas recalculadas.");
            }
        }));
    }

    /** Sustituye solo las rutas que nadie editó mientras se recalculaban. */
    private void aplicarServiciosRecalculados(List<EberthMServicioMicro> originales,
                                              List<EberthMServicioMicro> recalculadas) {
        List<EberthMServicioMicro> aplicadas =
                EberthMGestorServicios.fusionarRecalculadas(rutasMicros, originales, recalculadas);
        if (aplicadas.isEmpty()) return;
        aplicadas.forEach(nueva -> gestor.agregar(nueva.trayecto()));
        actualizarFilasRutasMicro();
        actualizarVisualizacionRutas();
        guardarRutasMicros();
    }

    // ==================================================================
    //  UTILIDADES
    // ==================================================================

    private Button boton(String texto, Runnable accion) {
        Button b = new Button(texto);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> ejecutarSeguro("acción '" + texto + "'", accion));
        return b;
    }

    private Button botonCompacto(String texto, Runnable accion) {
        Button b = new Button(texto);
        b.getStyleClass().add("boton-compacto");
        b.setOnAction(e -> ejecutarSeguro("acción '" + texto + "'", accion));
        return b;
    }

    /**
     * Ejecuta una acción capturando <b>cualquier</b> Throwable. Es la vía por la
     * que se ejecutan las acciones disparadas por el usuario.
     */
    private void ejecutarSeguro(String operacion, Runnable accion) {
        try {
            accion.run();
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "Falló " + operacion, t);
            try {
                lblEstado.setText("No se pudo completar " + operacion + ": "
                        + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            } catch (Throwable ignored) {
                // Ni siquiera actualizar el label puede tumbar la app.
            }
        }
    }

    private void mostrarError(String titulo, String mensaje) {
        Alert alerta = new Alert(Alert.AlertType.ERROR, mensaje, ButtonType.OK);
        estilizarDialogo(alerta);
        alerta.setTitle(titulo);
        alerta.setHeaderText(null);
        alerta.showAndWait();
    }

    private boolean confirmar(String titulo, String mensaje) {
        Alert alerta = new Alert(Alert.AlertType.CONFIRMATION, mensaje, ButtonType.OK, ButtonType.CANCEL);
        estilizarDialogo(alerta);
        alerta.setTitle(titulo);
        alerta.setHeaderText(null);
        return alerta.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    private void estilizarDialogo(Dialog<?> dialogo) {
        var hojaEstilos = getClass().getResource("/estilos/estilo.css");
        if (hojaEstilos != null) {
            String url = hojaEstilos.toExternalForm();
            if (!dialogo.getDialogPane().getStylesheets().contains(url)) {
                dialogo.getDialogPane().getStylesheets().add(url);
            }
        }
        dialogo.getDialogPane().getStyleClass().removeAll("theme-dark", "theme-light");
        dialogo.getDialogPane().getStyleClass().addAll("root-view",
                temaClaro ? "theme-light" : "theme-dark");
    }

    private String colorHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }

    private HBox crearStatusBar() {
        HBox bar = new HBox(12);
        bar.setPadding(new Insets(6, 12, 6, 12));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        progreso.setPrefSize(18, 18);
        progreso.setMaxSize(18, 18);
        progreso.setVisible(false);
        lblEstado.getStyleClass().add("status-item");
        lblCoords.getStyleClass().addAll("status-item", "texto-secundario");
        lblZoom.getStyleClass().addAll("status-item", "texto-secundario");
        lblInfoVista.getStyleClass().addAll("status-item", "texto-secundario");

        Region esp = new Region();
        HBox.setHgrow(esp, Priority.ALWAYS);

        bar.getChildren().addAll(
                progreso,
                lblEstado,
                esp,
                lblInfoVista,
                new Separator(Orientation.VERTICAL),
                lblCoords,
                new Separator(Orientation.VERTICAL),
                lblZoom);
        return bar;
    }

    // ==================================================================
    //  API PÚBLICA
    // ==================================================================

    public BorderPane getRoot() {
        return root;
    }

    public void cerrar() {
        try {
            relojUi.stop();
        } catch (Throwable ignored) { }
        try {
            motor.close();
        } catch (Throwable ignored) { }
        try {
            calculos.shutdownNow();
        } catch (Throwable ignored) { }
        try {
            mapa.cerrar();
        } catch (Throwable ignored) { }
    }

    private static final class OpcionExportacion {
        private final DanielTRutaExportable ruta;
        private final boolean visible;
        private boolean seleccionada;

        private OpcionExportacion(DanielTRutaExportable ruta, boolean seleccionada, boolean visible) {
            this.ruta = ruta;
            this.seleccionada = seleccionada;
            this.visible = visible;
        }
    }
}
