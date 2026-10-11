package trafiqueishon.ui;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Accordion;
import javafx.scene.control.ListView;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.FileChooser;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import trafiqueishon.simulacion.CargadorPuntosInteres;
import trafiqueishon.simulacion.DemandaVehicular;
import trafiqueishon.simulacion.EstadisticasSimulacion;
import trafiqueishon.simulacion.ImportadorExcelEmpresas;
import trafiqueishon.simulacion.MarcadorReferencia;
import trafiqueishon.simulacion.PersistenciaEscenario;
import trafiqueishon.simulacion.PuntoInteres;
import trafiqueishon.simulacion.ReporteRutaPdf;
import trafiqueishon.simulacion.RutaMicro;
import trafiqueishon.simulacion.RutaMicroStore;
import trafiqueishon.simulacion.SimuladorTrafico;
import trafiqueishon.simulacion.TipoVehiculo;
import trafiqueishon.geo.Nodo;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class SimulacionController {
    private final MapaCanvas mapa;
    private final Spinner<Integer> cantidadVehiculos = new Spinner<>();
    private final Spinner<Integer> limiteVehiculos = new Spinner<>();
    private final EnumMap<TipoVehiculo, Spinner<Double>> tasas = new EnumMap<>(TipoVehiculo.class);
    private final Slider velocidadSimulacion = new Slider(1, 20, 5);
    private final CheckBox aprendizajeSemaforos =
            new CheckBox("Semáforos adaptativos Q-learning");
    private final Button iniciar = new Button("Iniciar simulación");
    private final Button pausar = new Button("Pausar");
    private final Button reiniciar = new Button("Reiniciar");
    private final Label estado = new Label("Cargando la red vial...");
    private final Label lblActivos = new Label("0");
    private final Label lblViajes = new Label("0");
    private final Label lblTiempo = new Label("06:00");
    private final Label lblVelocidad = new Label("0 km/h");
    private final Label lblCongestion = new Label("0");
    private final Label lblEscenario = new Label("Esperando la red vial...");
    private final Label lblPuntos = new Label("");
    private final List<RutaMicro> rutasMicros = new ArrayList<>();
    private final RutaMicroStore rutaMicroStore = new RutaMicroStore();
    private final PersistenciaEscenario persistencia = new PersistenciaEscenario();
    private final List<Control> controlesConfiguracion = new ArrayList<>();
    private final TableView<RutaMicro> tablaMicros = new TableView<>();
    private ComboBox<PuntoInteres> microOrigen;
    private ComboBox<PuntoInteres> microDestino;
    private ComboBox<PuntoInteres> paradaDisponible;
    private ListView<PuntoInteres> paradasMicro;
    private TextField nombreMicro;
    private TextField empresaMicro;
    private TextField logoMicro;
    private Spinner<Integer> frecuenciaMicro;
    private Spinner<Integer> flotaMicro;
    private Button agregarMicro;
    private Button quitarMicro;
    private Button colocarSemaforos;
    private Button quitarSemaforoEnMapa;
    private ComboBox<String> filtroEmpresa;
    private final BiConsumer<EstadisticasSimulacion, Boolean> onEstadisticas;
    private final ScheduledExecutorService motor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread hilo = new Thread(runnable, "motor-simulacion");
                hilo.setDaemon(true);
                return hilo;
            });
    private AnimationTimer actualizadorFx;

    private volatile SimuladorTrafico simulador;
    private volatile SimuladorTrafico.FrameVehiculos framePendiente;
    private volatile EstadisticasSimulacion estadisticasPendientes;
    private volatile boolean ejecutandoPendiente;
    private volatile String errorMotor;
    private List<PuntoInteres> puntosInteres = List.of();
    private List<MarcadorReferencia> referenciasGuardadas = List.of();
    private Consumer<List<MarcadorReferencia>> onReferenciasActualizadas = ignored -> {};
    private volatile boolean preparando;
    private long generacionEscenario;

    public SimulacionController(MapaCanvas mapa,
                                BiConsumer<EstadisticasSimulacion, Boolean> onEstadisticas) {
        this.mapa = mapa;
        this.onEstadisticas = onEstadisticas;
        cantidadVehiculos.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 50_000, 250));
        cantidadVehiculos.setEditable(true);
        controlesConfiguracion.add(cantidadVehiculos);
        limiteVehiculos.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 50_000, 10_000));
        limiteVehiculos.setEditable(true);
        controlesConfiguracion.add(limiteVehiculos);
        for (TipoVehiculo tipo : TipoVehiculo.values()) {
            Spinner<Double> tasa = new Spinner<>();
            tasa.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 100_000,
                    switch (tipo) {
                        case PARTICULAR -> 16.0;
                        case MOTO_LINEAL -> 12.0;
                        case MOTOTAXI -> 20.0;
                        case CARGA -> 2.0;
                        case TRAILER -> 0.5;
                        case MICRO -> 0.0;
                    }, 1));
            tasa.setEditable(true);
            tasas.put(tipo, tasa);
            controlesConfiguracion.add(tasa);
        }

        velocidadSimulacion.setMajorTickUnit(5);
        velocidadSimulacion.setMinorTickCount(4);
        velocidadSimulacion.setSnapToTicks(true);
        velocidadSimulacion.setShowTickLabels(true);
        velocidadSimulacion.setShowTickMarks(true);
        velocidadSimulacion.valueProperty().addListener((observable, anterior, actual) -> {
            if (simulador != null) simulador.setEscalaTiempo(actual.doubleValue());
        });
        aprendizajeSemaforos.selectedProperty().addListener((observable, anterior, activo) -> {
            if (simulador != null) simulador.setAprendizajeSemaforos(activo);
        });

        iniciar.setOnAction(event -> iniciarSimulacion());
        pausar.setOnAction(event -> alternarPausa());
        reiniciar.setOnAction(event -> reiniciarSimulacion());
        pausar.setDisable(true);
        reiniciar.setDisable(true);
        iniciar.setDisable(true);

        mapa.setOnError(this::mostrarEstadoSeguro);
        mapa.setOnMapaCargado(this::inicializarEscenario);
        mapa.setOnQuitarSemaforo(nodoId -> {
            SimuladorTrafico activo = simulador;
            boolean quitado = activo != null && activo.quitarSemaforo(nodoId);
            if (activo != null) mapa.setSemaforos(activo.getSemaforos());
            if (quitarSemaforoEnMapa != null) {
                quitarSemaforoEnMapa.setText("Quitar semáforo: clic en el mapa");
            }
            mostrarEstadoSeguro(quitado
                    ? "Semáforo retirado de la intersección n.º " + nodoId + "."
                    : "La intersección seleccionada no tenía semáforo.");
        });

        actualizadorFx = new AnimationTimer() {
            private SimuladorTrafico.FrameVehiculos frameAnterior;
            private EstadisticasSimulacion estadisticasAnteriores;
            private long ultimoPulso;
            @Override
            public void handle(long ahora) {
                if (ahora - ultimoPulso < 33_333_333L) return;
                ultimoPulso = ahora;
                if (errorMotor != null) {
                    estado.setText(errorMotor);
                    errorMotor = null;
                }
                SimuladorTrafico.FrameVehiculos frame = framePendiente;
                if (frame != null && frame != frameAnterior) {
                    if (simulador != null) mapa.setSemaforos(simulador.getSemaforos());
                    mapa.setFrameVehiculos(frame);
                    frameAnterior = frame;
                }
                EstadisticasSimulacion estadisticas = estadisticasPendientes;
                if (estadisticas != null && estadisticas != estadisticasAnteriores) {
                    actualizarMetricas(estadisticas, ejecutandoPendiente);
                    estadisticasAnteriores = estadisticas;
                }
            }
        };
        actualizadorFx.start();
        motor.scheduleAtFixedRate(this::actualizarMotor, 0, 33, TimeUnit.MILLISECONDS);
    }

    private void actualizarMotor() {
        if (preparando) return;
        SimuladorTrafico activo = simulador;
        if (activo == null) return;
        try {
            long ahora = System.nanoTime();
            long deltaAnterior = ultimoTickMotor;
            ultimoTickMotor = ahora;
            if (deltaAnterior != 0 && activo.isEjecutando()) {
                activo.actualizar((ahora - deltaAnterior) / 1_000_000_000.0);
            }
            if (ahora - ultimoFrameMotor >= 33_333_333L
                    && (activo.isEjecutando() || framePendiente == null)) {
                framePendiente = activo.getFrame();
                ultimoFrameMotor = ahora;
            }
            if (ahora - ultimaMetricaMotor >= 500_000_000L) {
                estadisticasPendientes = activo.getEstadisticas();
                ejecutandoPendiente = activo.isEjecutando();
                ultimaMetricaMotor = ahora;
            }
        } catch (RuntimeException e) {
            activo.pausar();
            e.printStackTrace();
            errorMotor = "Simulación pausada por un error: " + e.getMessage();
        }
    }

    private long ultimoTickMotor;
    private long ultimoFrameMotor;
    private long ultimaMetricaMotor;

    public Node crearTabDemanda() {
        VBox contenido = crearContenedor();
        Label titulo = titulo("Demanda de tráfico");
        Label descripcion = new Label(
                "La flota recorre viajes entre los puntos de interés del escenario. "
                        + "La ocupación de los tramos modifica la velocidad y la congestión.");
        descripcion.setWrapText(true);

        HBox filaInicial = new HBox(10, new Label("Vehículos al inicio"), cantidadVehiculos);
        filaInicial.setAlignment(Pos.CENTER_LEFT);
        HBox filaMaximo = new HBox(10, new Label("Máximo simultáneo"), limiteVehiculos);
        filaMaximo.setAlignment(Pos.CENTER_LEFT);
        VBox tasasVista = new VBox(7);
        for (TipoVehiculo tipo : TipoVehiculo.values()) {
            if (tipo == TipoVehiculo.MICRO) continue;
            HBox fila = new HBox(8,
                    new Label(tipo.etiqueta()),
                    tasas.get(tipo),
                    new Label("vehículos/minuto"));
            fila.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(tasas.get(tipo), Priority.ALWAYS);
            tasasVista.getChildren().add(fila);
        }
        TableView<TipoVehiculo> matrizVial = new TableView<>(
                javafx.collections.FXCollections.observableArrayList(TipoVehiculo.values()));
        TableColumn<TipoVehiculo, String> tipoColumna = new TableColumn<>("Vehículo");
        tipoColumna.setCellValueFactory(data ->
                new javafx.beans.property.SimpleStringProperty(data.getValue().etiqueta()));
        TableColumn<TipoVehiculo, String> viasColumna = new TableColumn<>("Tipos de vía permitidos");
        viasColumna.setCellValueFactory(data ->
                new javafx.beans.property.SimpleStringProperty(
                        data.getValue().viasPermitidasDescripcion()));
        matrizVial.getColumns().setAll(tipoColumna, viasColumna);
        matrizVial.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        matrizVial.setPrefHeight(190);
        Label valorVelocidad = new Label("5x");
        velocidadSimulacion.valueProperty().addListener((observable, anterior, actual) ->
                valorVelocidad.setText(String.format("%.0fx", actual.doubleValue())));
        HBox encabezadoVelocidad = new HBox(8, new Label("Velocidad de simulación"), valorVelocidad);
        encabezadoVelocidad.setAlignment(Pos.CENTER_LEFT);

        VBox demandaVista = new VBox(8,
                filaInicial, filaMaximo,
                new Label("Generación por tipo de vehículo"), tasasVista);
        VBox parametrosVista = new VBox(8,
                aprendizajeSemaforos,
                new Label("Restricciones de circulación"), matrizVial);
        colocarSemaforos = new Button("Colocar semáforos sugeridos");
        colocarSemaforos.setOnAction(event -> {
            SimuladorTrafico activo = simulador;
            if (activo == null) {
                estado.setText("La red vial todavía no está lista.");
                return;
            }
            int agregados = activo.colocarSemaforosSugeridos();
            mapa.setSemaforos(activo.getSemaforos());
            estado.setText(agregados == 0
                    ? "Todos los semáforos sugeridos ya están colocados."
                    : "Se colocaron " + agregados
                            + " semáforos en cruces de mayor prioridad.");
        });
        quitarSemaforoEnMapa = new Button("Quitar semáforo: clic en el mapa");
        quitarSemaforoEnMapa.setOnAction(event -> {
            boolean activar = !mapa.isModoQuitarSemaforo();
            mapa.activarModoQuitarSemaforo(activar);
            quitarSemaforoEnMapa.setText(activar
                    ? "Selecciona un semáforo en el mapa..."
                    : "Quitar semáforo: clic en el mapa");
            estado.setText(activar
                    ? "Haz clic en el semáforo de la intersección que quieres quitar."
                    : "Selección de semáforo cancelada.");
        });
        HBox controlesSemaforos = new HBox(8, colocarSemaforos, quitarSemaforoEnMapa);
        controlesSemaforos.setAlignment(Pos.CENTER_LEFT);
        filtroEmpresa = new ComboBox<>();
        filtroEmpresa.getItems().add("Todas las empresas");
        filtroEmpresa.getSelectionModel().selectFirst();
        filtroEmpresa.setMaxWidth(Double.MAX_VALUE);
        filtroEmpresa.setOnAction(event ->
                mapa.setEmpresaFiltro(filtroEmpresa.getValue()));
        actualizarEmpresasFiltro();
        HBox selectorEmpresa = new HBox(8,
                new Label("Flota visible en el mapa"), filtroEmpresa);
        HBox.setHgrow(filtroEmpresa, Priority.ALWAYS);
        HBox botones = new HBox(8, iniciar, pausar, reiniciar);
        botones.setAlignment(Pos.CENTER_LEFT);
        estado.setWrapText(true);
        VBox ejecucionVista = new VBox(10,
                encabezadoVelocidad, velocidadSimulacion, botones,
                selectorEmpresa,
                new Separator(), new Label("Control de semáforos por intersección"),
                controlesSemaforos,
                new Separator(), estado);
        Accordion secciones = new Accordion(
                new TitledPane("Demanda y flota", demandaVista),
                new TitledPane("Tipos de vehículo y semáforos", parametrosVista),
                new TitledPane("Simulación y tráfico", ejecucionVista));
        secciones.setExpandedPane(secciones.getPanes().getFirst());
        contenido.getChildren().addAll(titulo, descripcion, new Separator(), secciones);
        return envolverEnScroll(contenido);
    }

    public ScrollPane crearVentanaMicros() {
        return (ScrollPane) crearTabMicros();
    }

    public Node crearTabMicros() {
        VBox contenido = crearContenedor();
        nombreMicro = new TextField();
        nombreMicro.setPromptText("Nombre de la ruta");
        empresaMicro = new TextField();
        empresaMicro.setPromptText("Empresa de transporte");
        logoMicro = new TextField();
        logoMicro.setPromptText("Logo (archivo PNG/JPG opcional)");
        logoMicro.setEditable(false);
        Button elegirLogo = new Button("Seleccionar logo");
        elegirLogo.setOnAction(event -> {
            FileChooser selector = new FileChooser();
            selector.setTitle("Seleccionar el logotipo de la ruta");
            selector.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Imagenes", "*.png", "*.jpg", "*.jpeg"));
            java.io.File archivo = selector.showOpenDialog(logoMicro.getScene().getWindow());
            if (archivo != null) logoMicro.setText(archivo.getAbsolutePath());
        });
        microOrigen = new ComboBox<>();
        microDestino = new ComboBox<>();
        paradaDisponible = new ComboBox<>();
        configurarCeldaPuntos(microOrigen);
        configurarCeldaPuntos(microDestino);
        configurarCeldaPuntos(paradaDisponible);
        microOrigen.setPromptText("Origen");
        microDestino.setPromptText("Destino");
        paradasMicro = new ListView<>();
        paradasMicro.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(PuntoInteres punto, boolean vacio) {
                super.updateItem(punto, vacio);
                setText(vacio || punto == null ? null : punto.nombre());
            }
        });
        paradasMicro.setPrefHeight(120);
        Button agregarParada = new Button("Agregar parada");
        agregarParada.setOnAction(event -> {
            PuntoInteres parada = paradaDisponible.getValue();
            if (parada != null && paradasMicro.getItems().stream()
                    .noneMatch(punto -> punto.id().equals(parada.id()))) {
                paradasMicro.getItems().add(parada);
            }
        });
        Button quitarParada = new Button("Quitar");
        quitarParada.setOnAction(event -> {
            int indice = paradasMicro.getSelectionModel().getSelectedIndex();
            if (indice >= 0) paradasMicro.getItems().remove(indice);
        });
        Button subirParada = new Button("Subir");
        subirParada.setOnAction(event -> moverParada(-1));
        Button bajarParada = new Button("Bajar");
        bajarParada.setOnAction(event -> moverParada(1));
        frecuenciaMicro = new Spinner<>(1, 240, 8);
        flotaMicro = new Spinner<>(1, 500, 10);
        controlesConfiguracion.addAll(List.of(empresaMicro, nombreMicro, logoMicro, microOrigen, microDestino,
                paradaDisponible, paradasMicro, frecuenciaMicro, flotaMicro));
        actualizarPuntosMicro();
        agregarMicro = new Button("Agregar ruta de micro");
        agregarMicro.setOnAction(event -> agregarRutaMicro());
        quitarMicro = new Button("Eliminar ruta seleccionada");
        quitarMicro.setOnAction(event -> {
            RutaMicro seleccionada = tablaMicros.getSelectionModel().getSelectedItem();
            if (seleccionada != null) {
                rutasMicros.remove(seleccionada);
                tablaMicros.getItems().remove(seleccionada);
                guardarMicros();
            }
        });
        Button exportarReporte = new Button("Generar informe PDF de la ruta óptima");
        exportarReporte.setOnAction(event -> exportarReporteMicros());
        Button importarExcel = new Button("Importar rutas desde Excel");
        importarExcel.setOnAction(event -> importarRutasExcel());
        Button crearPlantilla = new Button("Crear plantilla Excel");
        crearPlantilla.setOnAction(event -> guardarPlantillaExcel());
        Label ayudaExcel = new Label("Cada hoja representa una empresa. Columnas: Ruta, "
                + "Origen, Destino, Puntos intermedios, Tipo de vehículo, Frecuencia "
                + "(minutos) y Unidades. Ordena las paradas con comas o guiones.");
        ayudaExcel.setWrapText(true);
        configurarTablaMicros();

        contenido.getChildren().addAll(
                titulo("Gestión de micros"), empresaMicro, nombreMicro,
                new HBox(8, logoMicro, elegirLogo),
                new Label("Origen"), microOrigen,
                new Label("Destino"), microDestino,
                new Label("Agregar parada intermedia"), paradaDisponible,
                new HBox(8, agregarParada, quitarParada, subirParada, bajarParada),
                new Label("Orden de recorrido"), paradasMicro,
                filaCampo("Frecuencia (minutos)", frecuenciaMicro),
                filaCampo("Unidades en flota", flotaMicro),
                ayudaExcel,
                new HBox(8, agregarMicro, quitarMicro),
                new HBox(8, importarExcel, crearPlantilla), exportarReporte, tablaMicros,
                new Label("Las rutas respetan sentidos únicos y restricciones para micros."));
        VBox.setVgrow(tablaMicros, Priority.ALWAYS);
        return envolverEnScroll(contenido);
    }

    private Node envolverEnScroll(VBox contenido) {
        ScrollPane scroll = new ScrollPane(contenido);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("scroll-panel");
        return scroll;
    }

    private void configurarTablaMicros() {
        TableColumn<RutaMicro, String> nombre = new TableColumn<>("Ruta");
        nombre.setCellValueFactory(data ->
                new javafx.beans.property.SimpleStringProperty(data.getValue().nombre()));
        TableColumn<RutaMicro, String> empresa = new TableColumn<>("Empresa");
        empresa.setCellValueFactory(data ->
                new javafx.beans.property.SimpleStringProperty(data.getValue().empresa()));
        TableColumn<RutaMicro, String> recorrido = new TableColumn<>("Puntos");
        recorrido.setCellValueFactory(data ->
                new javafx.beans.property.SimpleStringProperty(
                        data.getValue().puntosIntermedios().stream()
                                .map(this::nombrePunto)
                                .collect(java.util.stream.Collectors.joining(" > "))));
        TableColumn<RutaMicro, Integer> frecuencia = new TableColumn<>("min");
        frecuencia.setCellValueFactory(data ->
                new javafx.beans.property.SimpleIntegerProperty(
                        data.getValue().frecuenciaMinutos()).asObject());
        TableColumn<RutaMicro, Integer> unidades = new TableColumn<>("Flota");
        unidades.setCellValueFactory(data ->
                new javafx.beans.property.SimpleIntegerProperty(
                        data.getValue().unidades()).asObject());
        TableColumn<RutaMicro, String> logo = new TableColumn<>("Logo");
        logo.setCellValueFactory(data ->
                new javafx.beans.property.SimpleStringProperty(
                        data.getValue().logoPath().isBlank() ? "Sin logo" : "Logo cargado"));
        tablaMicros.getColumns().setAll(empresa, nombre, recorrido, frecuencia, unidades, logo);
        tablaMicros.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        tablaMicros.setItems(javafx.collections.FXCollections.observableArrayList(rutasMicros));
    }

    private void agregarRutaMicro() {
        if (microOrigen.getValue() == null || microDestino.getValue() == null
                || microOrigen.getValue().id().equals(microDestino.getValue().id())) {
            estado.setText("Selecciona origen y destino distintos para el micro.");
            return;
        }
        List<String> puntos = new ArrayList<>();
        puntos.add(microOrigen.getValue().id());
        paradasMicro.getItems().stream()
                .map(PuntoInteres::id)
                .filter(id -> !id.equals(microOrigen.getValue().id())
                        && !id.equals(microDestino.getValue().id()))
                .forEach(puntos::add);
        puntos.add(microDestino.getValue().id());
        try {
            String logoPersistido = rutaMicroStore.importarLogo(logoMicro.getText());
            RutaMicro ruta = new RutaMicro(nombreMicro.getText().isBlank()
                    ? "Micro " + (rutasMicros.size() + 1) : nombreMicro.getText().trim(),
                    empresaMicro.getText(), puntos, frecuenciaMicro.getValue(),
                    flotaMicro.getValue(), logoPersistido);
            rutasMicros.add(ruta);
            tablaMicros.getItems().add(ruta);
            if (!guardarMicros()) return;
            nombreMicro.clear();
            logoMicro.clear();
            paradasMicro.getItems().clear();
            actualizarEmpresasFiltro();
            estado.setText("Ruta de micro agregada al escenario.");
        } catch (IllegalArgumentException e) {
            estado.setText(e.getMessage());
        } catch (java.io.IOException e) {
            estado.setText("No se pudo importar el logo: " + e.getMessage());
        }
    }

    private void importarRutasExcel() {
        if (preparando) {
            estado.setText("Espera a que termine la preparación de la simulación.");
            return;
        }
        FileChooser selector = new FileChooser();
        selector.setTitle("Importar rutas por empresa");
        selector.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Libro Excel (*.xlsx)", "*.xlsx"));
        java.io.File archivo = selector.showOpenDialog(tablaMicros.getScene().getWindow());
        if (archivo == null) return;

        Task<ImportadorExcelEmpresas.ResultadoImportacion> tarea = new Task<>() {
            @Override
            protected ImportadorExcelEmpresas.ResultadoImportacion call() throws Exception {
                return new ImportadorExcelEmpresas().importar(archivo.toPath(), puntosInteres);
            }
        };
        tarea.setOnRunning(event -> estado.setText("Validando las hojas y rutas del libro Excel..."));
        tarea.setOnSucceeded(event -> {
            ImportadorExcelEmpresas.ResultadoImportacion resultado = tarea.getValue();
            if (resultado.rutas().isEmpty()) {
                estado.setText(resultado.errores().isEmpty()
                        ? "El libro no contiene rutas para importar."
                        : "No se importaron rutas; revisa los errores del libro.");
                mostrarErroresImportacion(resultado);
                return;
            }
            List<RutaMicro> rutasActualizadas = new ArrayList<>(rutasMicros);
            rutasActualizadas.addAll(resultado.rutas());
            try {
                actualizarServiciosMicros(rutasActualizadas);
            } catch (RuntimeException ex) {
                estado.setText("No se importaron las rutas porque no se pueden recorrer "
                        + "en esta red: " + ex.getMessage());
                return;
            }
            rutasMicros.clear();
            rutasMicros.addAll(rutasActualizadas);
            tablaMicros.getItems().setAll(rutasMicros);
            actualizarEmpresasFiltro();
            estado.setText("Se importaron " + resultado.rutas().size()
                    + " rutas de " + resultado.rutas().stream()
                    .map(RutaMicro::empresa).distinct().count() + " empresas."
                    + (resultado.hojasOmitidas().isEmpty() ? ""
                    : " Hojas vacias omitidas: " + String.join(", ", resultado.hojasOmitidas()) + "."));
            if (!guardarMicros()) return;
            mostrarErroresImportacion(resultado);
        });
        tarea.setOnFailed(event -> estado.setText("No se pudo importar Excel: "
                + tarea.getException().getMessage()));
        Thread hilo = new Thread(tarea, "importador-excel-empresas");
        hilo.setDaemon(true);
        hilo.start();
    }

    private void guardarPlantillaExcel() {
        FileChooser selector = new FileChooser();
        selector.setTitle("Guardar plantilla de rutas");
        selector.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Libro Excel (*.xlsx)", "*.xlsx"));
        selector.setInitialFileName("plantilla-rutas-empresas.xlsx");
        java.io.File archivo = selector.showSaveDialog(tablaMicros.getScene().getWindow());
        if (archivo == null) return;
        try {
            new ImportadorExcelEmpresas().crearPlantilla(archivo.toPath());
            estado.setText("Plantilla Excel creada: " + archivo.getAbsolutePath());
        } catch (java.io.IOException ex) {
            estado.setText("No se pudo crear la plantilla Excel: " + ex.getMessage());
        }
    }

    private void mostrarErroresImportacion(
            ImportadorExcelEmpresas.ResultadoImportacion resultado) {
        if (resultado.errores().isEmpty()) return;
        javafx.scene.control.Alert alerta =
                new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
        alerta.setTitle("Validacion del libro Excel");
        alerta.setHeaderText(resultado.errores().size() + " fila(s) requieren correccion");
        alerta.setContentText(String.join("\n", resultado.errores().stream().limit(12).toList()));
        alerta.show();
    }

    private void actualizarEmpresasFiltro() {
        if (filtroEmpresa == null) return;
        String seleccionada = filtroEmpresa.getValue();
        List<String> opciones = rutasMicros.stream().map(RutaMicro::empresa).distinct().sorted()
                .toList();
        filtroEmpresa.getItems().setAll("Todas las empresas");
        filtroEmpresa.getItems().addAll(opciones);
        if (seleccionada != null && filtroEmpresa.getItems().contains(seleccionada)) {
            filtroEmpresa.getSelectionModel().select(seleccionada);
        } else {
            filtroEmpresa.getSelectionModel().selectFirst();
        }
    }

    private void actualizarServiciosMicros(List<RutaMicro> rutasActualizadas) {
        if (simulador == null) return;
        simulador.reiniciar();
        simulador.configurarMicros(List.copyOf(rutasActualizadas));
        framePendiente = null;
        mapa.setFrameVehiculos(null);
        estadisticasPendientes = simulador.getEstadisticas();
        ejecutandoPendiente = false;
        iniciar.setText("Iniciar simulación");
        iniciar.setDisable(false);
        pausar.setText("Pausar");
        pausar.setDisable(true);
        reiniciar.setDisable(true);
        bloquearConfiguracion(false);
        estado.setText("La flota se actualizó; la simulación se reinició.");
    }

    private boolean guardarMicros() {
        try {
            rutaMicroStore.guardar(List.copyOf(rutasMicros));
            return true;
        } catch (java.io.IOException e) {
            estado.setText("No se pudieron guardar las rutas de micros: " + e.getMessage());
            return false;
        }
    }

    private void actualizarPuntosMicro() {
        if (microOrigen == null) return;
        microOrigen.setItems(javafx.collections.FXCollections.observableArrayList(puntosInteres));
        microDestino.setItems(javafx.collections.FXCollections.observableArrayList(puntosInteres));
        paradaDisponible.setItems(javafx.collections.FXCollections.observableArrayList(puntosInteres));
        paradasMicro.getItems().clear();
        if (puntosInteres.size() >= 2) {
            microOrigen.getSelectionModel().select(0);
            microDestino.getSelectionModel().select(1);
        }
    }

    private void configurarCeldaPuntos(ComboBox<PuntoInteres> selector) {
            selector.setCellFactory(ignored -> new ListCell<>() {
                @Override
                protected void updateItem(PuntoInteres punto, boolean vacio) {
                    super.updateItem(punto, vacio);
                    setText(vacio || punto == null ? null : punto.nombre());
                }

            });
            selector.setButtonCell(new ListCell<>() {
                @Override
                protected void updateItem(PuntoInteres punto, boolean vacio) {
                    super.updateItem(punto, vacio);
                    setText(vacio || punto == null ? null : punto.nombre());
                }
            });
    }

    private void moverParada(int desplazamiento) {
        int indice = paradasMicro.getSelectionModel().getSelectedIndex();
        int destino = indice + desplazamiento;
        if (indice < 0 || destino < 0 || destino >= paradasMicro.getItems().size()) return;
        PuntoInteres parada = paradasMicro.getItems().remove(indice);
        paradasMicro.getItems().add(destino, parada);
        paradasMicro.getSelectionModel().select(destino);
    }

    private String nombrePunto(String id) {
            return puntosInteres.stream().filter(punto -> punto.id().equals(id))
                    .map(PuntoInteres::nombre).findFirst().orElse(id);
    }

    private void exportarReporteMicros() {
            RutaMicro ruta = tablaMicros.getSelectionModel().getSelectedItem();
            if (ruta == null) {
                estado.setText("Selecciona una ruta de micro antes de generar el informe.");
                return;
            }
            if (mapa.getGrafo() == null) {
                estado.setText("La red vial todavía no está lista.");
                return;
            }
            FileChooser selector = new FileChooser();
            selector.setTitle("Guardar informe de ruta");
            selector.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Documento PDF", "*.pdf"));
            selector.setInitialFileName(ruta.nombre().replaceAll("[^A-Za-z0-9_-]", "_") + ".pdf");
            java.io.File archivo = selector.showSaveDialog(tablaMicros.getScene().getWindow());
            if (archivo == null) return;
            try {
                new ReporteRutaPdf().generar(ruta, puntosInteres, mapa.getGrafo(), archivo.toPath());
                estado.setText("Reporte PDF generado: " + archivo.getAbsolutePath());
            } catch (java.io.IOException | RuntimeException e) {
                estado.setText("No se pudo generar el reporte: " + e.getMessage());
        }
    }

    private HBox filaCampo(String etiqueta, Node control) {
        HBox fila = new HBox(10, new Label(etiqueta), control);
        fila.setAlignment(Pos.CENTER_LEFT);
        return fila;
    }

    public Node crearTabMetricas() {
        VBox contenido = crearContenedor();
        contenido.getChildren().addAll(
                titulo("Métricas en vivo"),
                filaMetrica("Vehículos activos", lblActivos),
                filaMetrica("Viajes completados", lblViajes),
                filaMetrica("Hora simulada", lblTiempo),
                filaMetrica("Velocidad media", lblVelocidad),
                filaMetrica("Tramos congestionados", lblCongestion),
                new Separator(),
                new Label("La congestión se estima a partir de vehículos por tramo; "
                        + "no representa mediciones de campo."));
        return contenido;
    }

    public Node crearTabEscenario() {
        VBox contenido = crearContenedor();
        lblEscenario.setWrapText(true);
        lblPuntos.setWrapText(true);
        contenido.getChildren().addAll(
                titulo("Escenario actual"), lblEscenario,
                new Separator(), titulo("Puntos de demanda"), lblPuntos,
                new Separator(),
                new Label("Verifica las coordenadas y los pesos de los POI con datos locales."),
                new Label("Red vial: OpenStreetMap, licencia ODbL. "
                        + "Las velocidades y la capacidad son estimaciones."));
        return contenido;
    }

    private VBox crearContenedor() {
        VBox contenido = new VBox(12);
        contenido.setPadding(new Insets(15));
        contenido.getStyleClass().add("tab-contenido");
        return contenido;
    }

    private Label titulo(String texto) {
        Label label = new Label(texto);
        label.getStyleClass().add("tab-titulo");
        return label;
    }

    private HBox filaMetrica(String nombre, Label valor) {
        HBox fila = new HBox(10, new Label(nombre), valor);
        HBox.setHgrow(fila.getChildren().get(0), Priority.ALWAYS);
        fila.setAlignment(Pos.CENTER_LEFT);
        return fila;
    }

    private void inicializarEscenario() {
        generacionEscenario++;
        preparando = false;
        try {
            List<PuntoInteres> base = new CargadorPuntosInteres()
                    .cargarRecurso("/datos/poi.json");
            referenciasGuardadas = persistencia.cargarReferencias();
            puntosInteres = construirPuntosEscenario(mapa.getGrafo(), base, referenciasGuardadas);
            mapa.setReferencias(referenciasGuardadas.stream()
                    .filter(ref -> estaDentro(mapa.getGrafo(), ref.lon(), ref.lat())).toList());
            onReferenciasActualizadas.accept(referenciasGuardadas);
            cargarPreferencias();
            rutasMicros.clear();
            Set<String> idsDisponibles = puntosInteres.stream()
                    .map(PuntoInteres::id).collect(java.util.stream.Collectors.toSet());
            List<RutaMicro> rutasGuardadas = rutaMicroStore.cargar();
            rutasMicros.addAll(rutasGuardadas.stream()
                    .filter(ruta -> idsDisponibles.containsAll(ruta.puntosIntermedios()))
                    .toList());
            boolean rutasOmitidas = rutasMicros.size() < rutasGuardadas.size();
            tablaMicros.getItems().setAll(rutasMicros);
            actualizarEmpresasFiltro();
            simulador = new SimuladorTrafico(mapa.getGrafo(), puntosInteres);
            simulador.setAprendizajeSemaforos(aprendizajeSemaforos.isSelected());
            mapa.setSemaforos(simulador.getSemaforos());
            simulador.setEscalaTiempo(velocidadSimulacion.getValue());
            actualizarPuntosMicro();

            iniciar.setDisable(false);
            estado.setText(rutasOmitidas
                    ? "Escenario listo; se omitieron rutas guardadas incompatibles con este mapa."
                    : "Escenario listo. Puedes iniciar la simulación.");
            lblEscenario.setText("Red " + mapa.getNombreMapa() + " cargada: "
                    + mapa.getGrafo().numNodos() + " nodos y "
                    + mapa.getGrafo().numEdges() + " tramos dirigidos.");
            lblPuntos.setText(puntosInteres.stream()
                    .map(punto -> punto.nombre() + " (" + punto.id()
                            + ", peso " + punto.peso() + ")")
                    .reduce((a, b) -> a + "\n" + b).orElse("Sin puntos configurados."));
        } catch (Exception e) {
            e.printStackTrace();
            iniciar.setDisable(true);
            estado.setText("No se pudo preparar el escenario: " + e.getMessage());
            lblEscenario.setText(estado.getText());
        }
    }

    private List<PuntoInteres> construirPuntosEscenario(
            trafiqueishon.geo.GrafoVial grafo, List<PuntoInteres> base,
            List<MarcadorReferencia> referencias) {
        List<PuntoInteres> resultado = new ArrayList<>();
        base.stream().filter(punto -> estaDentro(grafo, punto.lon(), punto.lat()))
                .forEach(resultado::add);
        for (MarcadorReferencia referencia : referencias) {
            if (!estaDentro(grafo, referencia.lon(), referencia.lat())) continue;
            String id = idReferencia(referencia);
            resultado.add(new PuntoInteres(id, referencia.nombre(),
                    referencia.lon(), referencia.lat()));
        }
        Set<Integer> nodos = new java.util.HashSet<>();
        resultado.removeIf(punto -> {
            Nodo nodo = grafo.nodoMasCercanoLonLat(punto.lon(), punto.lat());
            return nodo == null || !nodos.add(nodo.id);
        });
        if (resultado.size() >= 2) return List.copyOf(resultado);
        Nodo origen = grafo.getNodos().getFirst();
        Nodo destino = grafo.getNodos().stream()
                .max(java.util.Comparator.comparingDouble(nodo ->
                        distanciaCuadrada(origen, nodo))).orElseThrow();
        return List.of(
                new PuntoInteres("red_origen", "Origen de red", origen.lon, origen.lat),
                new PuntoInteres("red_destino", "Destino de red", destino.lon, destino.lat));
    }

    private boolean estaDentro(trafiqueishon.geo.GrafoVial grafo, double lon, double lat) {
        return lon >= grafo.getMinLon() && lon <= grafo.getMaxLon()
                && lat >= grafo.getMinLat() && lat <= grafo.getMaxLat();
    }

    private double distanciaCuadrada(Nodo a, Nodo b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return dx * dx + dy * dy;
    }

    private String idReferencia(MarcadorReferencia referencia) {
        long hash = 31 * Double.doubleToLongBits(referencia.lon())
                + Double.doubleToLongBits(referencia.lat());
        return "referencia_" + Long.toUnsignedString(hash, 36);
    }

    private void cargarPreferencias() throws java.io.IOException {
        PersistenciaEscenario.Preferencias guardadas = persistencia.cargarPreferencias();
        cantidadVehiculos.getValueFactory().setValue(Math.clamp(guardadas.flotaInicial(), 0, 50_000));
        limiteVehiculos.getValueFactory().setValue(Math.clamp(guardadas.limiteVehiculos(), 1, 50_000));
        velocidadSimulacion.setValue(Math.clamp(guardadas.escalaTiempo(), 1, 20));
        guardadas.tasas().forEach((nombre, valor) -> {
            try {
                TipoVehiculo tipo = TipoVehiculo.valueOf(nombre);
                tasas.get(tipo).getValueFactory().setValue(Math.clamp(valor, 0, 100_000));
            } catch (IllegalArgumentException ignored) {
                estado.setText("Se ignoro una tasa guardada para una clase desconocida: " + nombre);
            }
        });
        aprendizajeSemaforos.setSelected(guardadas.semaforosAdaptativos());
    }

    private void guardarPreferencias() throws java.io.IOException {
        java.util.Map<String, Double> tasasGuardadas = new java.util.HashMap<>();
        tasas.forEach((tipo, spinner) -> tasasGuardadas.put(tipo.name(), spinner.getValue()));
        persistencia.guardarPreferencias(new PersistenciaEscenario.Preferencias(
                cantidadVehiculos.getValue(), limiteVehiculos.getValue(),
                velocidadSimulacion.getValue(), tasasGuardadas,
                aprendizajeSemaforos.isSelected()));
    }

    public void setOnReferenciasActualizadas(Consumer<List<MarcadorReferencia>> callback) {
        onReferenciasActualizadas = callback == null ? ignored -> {} : callback;
        if (!referenciasGuardadas.isEmpty()) {
            onReferenciasActualizadas.accept(referenciasGuardadas);
        }
    }

    public void agregarPuntoReferencia(MarcadorReferencia referencia) {
        if (preparando || mapa.getGrafo() == null) {
            estado.setText("Espera a que termine la preparación antes de agregar puntos al escenario.");
            return;
        }
        List<PuntoInteres> actualizados = new ArrayList<>(puntosInteres);
        String id = idReferencia(referencia);
        if (actualizados.stream().anyMatch(punto -> punto.id().equals(id))) {
            estado.setText("Ya existe una referencia con estas coordenadas.");
            return;
        }
        actualizados.add(new PuntoInteres(id, referencia.nombre(),
                referencia.lon(), referencia.lat()));
        puntosInteres = List.copyOf(actualizados);
        List<MarcadorReferencia> referencias = new ArrayList<>(referenciasGuardadas);
        referencias.add(referencia);
        referenciasGuardadas = List.copyOf(referencias);
        try {
            persistencia.guardarReferencias(referenciasGuardadas);
        } catch (java.io.IOException e) {
            estado.setText("La referencia se agrego, pero no se pudo guardar: " + e.getMessage());
        }
        onReferenciasActualizadas.accept(referenciasGuardadas);

        if (simulador != null) {
            simulador.reiniciar();
            simulador = new SimuladorTrafico(mapa.getGrafo(), puntosInteres);
            simulador.setEscalaTiempo(velocidadSimulacion.getValue());
        }
        actualizarPuntosMicro();
        framePendiente = null;
        estadisticasPendientes = simulador == null ? null : simulador.getEstadisticas();
        ejecutandoPendiente = false;
        mapa.setFrameVehiculos(null);
        iniciar.setDisable(simulador == null);
        iniciar.setText("Iniciar simulación");
        pausar.setDisable(true);
        reiniciar.setDisable(true);
        bloquearConfiguracion(false);
        lblPuntos.setText(puntosInteres.stream()
                .map(punto -> punto.nombre() + " (" + punto.id()
                        + ", peso " + punto.peso() + ")")
                .reduce((a, b) -> a + "\n" + b).orElse("Sin puntos configurados."));
        estado.setText("Punto agregado al escenario. La simulación se reinició.");
    }

    private void iniciarSimulacion() {
        if (simulador == null || preparando) return;
        if (iniciar.getText().equals("Reanudar")) {
            simulador.reanudar();
            iniciar.setDisable(true);
            iniciar.setText("Iniciar simulación");
            pausar.setText("Pausar");
            estado.setText("Simulacion en curso.");
            actualizarMetricas();
            return;
        }
        final int cantidad;
        final int maximo;
        final double escala;
        final List<DemandaVehicular> configuracion;
        final List<RutaMicro> micros;
        try {
            cantidadVehiculos.commitValue();
            limiteVehiculos.commitValue();
            maximo = limiteVehiculos.getValue();
            cantidad = cantidadVehiculos.getValue();
            if (cantidad > maximo) {
                throw new IllegalArgumentException(
                        "La flota inicial no puede superar el máximo simultáneo.");
            }
            escala = velocidadSimulacion.getValue();
            List<DemandaVehicular> demanda = new ArrayList<>();
            for (TipoVehiculo tipo : TipoVehiculo.values()) {
                if (tipo == TipoVehiculo.MICRO) continue;
                tasas.get(tipo).commitValue();
                demanda.add(new DemandaVehicular(tipo, tasas.get(tipo).getValue()));
            }
            configuracion = List.copyOf(demanda);
            micros = List.copyOf(rutasMicros);
        } catch (RuntimeException e) {
            estado.setText("Revisa la cantidad de vehículos: " + e.getMessage());
            return;
        }

        preparando = true;
        iniciar.setDisable(true);
        estado.setText("Preparando las rutas de los vehículos...");
        SimuladorTrafico simuladorPreparado = simulador;
        long generacionPreparacion = generacionEscenario;
        Task<Void> tarea = new Task<>() {
            @Override
            protected Void call() throws Exception {
                try {
                    simuladorPreparado.setLimiteVehiculos(maximo);
                    simuladorPreparado.configurarDemanda(configuracion);
                    simuladorPreparado.configurarMicros(micros);
                    simuladorPreparado.setEscalaTiempo(escala);
                    simuladorPreparado.iniciar(cantidad);
                    return null;
                } catch (Exception e) {
                    e.printStackTrace();
                    throw e;
                }
            }
        };
        tarea.setOnSucceeded(event -> {
            if (generacionPreparacion != generacionEscenario
                    || simulador != simuladorPreparado) {
                simuladorPreparado.reiniciar();
                return;
            }
            preparando = false;
            iniciar.setDisable(true);
            bloquearConfiguracion(true);
            pausar.setDisable(false);
            pausar.setText("Pausar");
            iniciar.setText("Iniciar simulación");
            reiniciar.setDisable(false);
            estado.setText("Simulacion en curso.");
            framePendiente = simuladorPreparado.getFrame();
            estadisticasPendientes = simuladorPreparado.getEstadisticas();
            ejecutandoPendiente = true;
        });
        tarea.setOnFailed(event -> {
            if (generacionPreparacion != generacionEscenario
                    || simulador != simuladorPreparado) {
                simuladorPreparado.reiniciar();
                return;
            }
            preparando = false;
            bloquearConfiguracion(false);
            iniciar.setDisable(false);
            Throwable error = tarea.getException();
            simuladorPreparado.reiniciar();
            mapa.setFrameVehiculos(null);
            framePendiente = null;
            estadisticasPendientes = simuladorPreparado.getEstadisticas();
            ejecutandoPendiente = false;
            bloquearConfiguracion(false);
            pausar.setDisable(true);
            reiniciar.setDisable(true);
            estado.setText("No se pudo iniciar la simulación: "
                    + (error == null ? "error no especificado" : error.getMessage()));
        });
        Thread hilo = new Thread(tarea, "preparador-simulacion");
        hilo.setDaemon(true);
        hilo.start();
    }

    private void alternarPausa() {
        if (simulador == null) return;
        if (simulador.isEjecutando()) {
            simulador.pausar();
            pausar.setText("Reanudar");
            iniciar.setDisable(false);
            iniciar.setText("Reanudar");
            estado.setText("Simulación pausada.");
        } else {
            simulador.reanudar();
            pausar.setText("Pausar");
            iniciar.setDisable(true);
            iniciar.setText("Iniciar simulación");
            estado.setText("Simulacion en curso.");
        }
    }

    private void reiniciarSimulacion() {
        if (simulador == null || preparando) return;
        simulador.reiniciar();
        mapa.setFrameVehiculos(null);
        framePendiente = null;
        estadisticasPendientes = simulador.getEstadisticas();
        ejecutandoPendiente = false;
        iniciar.setDisable(false);
        iniciar.setText("Iniciar simulación");
        pausar.setText("Pausar");
        pausar.setDisable(true);
        reiniciar.setDisable(true);
        estado.setText("Simulación reiniciada.");
        actualizarMetricas();
    }

    public void aplicarRutaPreferida(trafiqueishon.geo.RutaResult ruta) {
        SimuladorTrafico activo = simulador;
        if (activo == null) {
            estado.setText("La red vial todavía no está lista.");
            return;
        }
        activo.configurarRutaPreferida(ruta);
        framePendiente = null;
        estado.setText(ruta == null
                ? "Se desactivó la preferencia por la ruta calculada."
                : "Los próximos viajes favorecerán el corredor seleccionado.");
    }

    private void actualizarMetricas() {
        if (simulador == null) return;
        actualizarMetricas(simulador.getEstadisticas(), simulador.isEjecutando());
    }

    private void actualizarMetricas(EstadisticasSimulacion estadisticas, boolean ejecutando) {
        lblActivos.setText(Integer.toString(estadisticas.vehiculosActivos()));
        lblViajes.setText(Long.toString(estadisticas.viajesCompletados()));
        lblTiempo.setText(formatearHora(estadisticas.tiempoSimuladoSegundos()));
        lblVelocidad.setText(String.format("%.1f km/h", estadisticas.velocidadMediaKmH()));
        lblCongestion.setText(Integer.toString(estadisticas.tramosCongestionados()));
        if (onEstadisticas != null) {
            onEstadisticas.accept(estadisticas, ejecutando);
        }
    }

    private void mostrarEstadoSeguro(String mensaje) {
        if (Platform.isFxApplicationThread()) {
            estado.setText(mensaje);
        } else {
            Platform.runLater(() -> estado.setText(mensaje));
        }
    }

    private void bloquearConfiguracion(boolean bloquear) {
        controlesConfiguracion.forEach(control -> control.setDisable(bloquear));
        if (agregarMicro != null) agregarMicro.setDisable(bloquear);
        if (quitarMicro != null) quitarMicro.setDisable(bloquear);
        tablaMicros.setDisable(bloquear);
    }

    public void cerrar() {
        if (actualizadorFx != null) actualizadorFx.stop();
        try {
            guardarPreferencias();
        } catch (java.io.IOException e) {
            System.err.println("No se pudieron guardar preferencias: " + e.getMessage());
        }
        motor.shutdownNow();
    }

    private String formatearHora(double segundos) {
        long minutos = 6 * 60 + (long) (segundos / 60);
        return String.format("%02d:%02d", (minutos / 60) % 24, minutos % 60);
    }
}
