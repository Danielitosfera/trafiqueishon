package trafiqueishon.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.util.StringConverter;
import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.simulacion.EberthMDemandaCiudad;
import trafiqueishon.simulacion.EberthMGeneradorRutas;
import trafiqueishon.simulacion.EberthMGeneradorRutas.Opciones;
import trafiqueishon.simulacion.EberthMGeneradorRutas.TipoRuta;
import trafiqueishon.simulacion.EberthMGeneradorRutas.TiposRuta;
import trafiqueishon.simulacion.EberthMParametrosCiudad;
import trafiqueishon.simulacion.EberthMPoi;
import trafiqueishon.simulacion.EberthMServicioMicro;
import trafiqueishon.simulacion.EberthMTipoDia;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * «Autogenerar rutas» paso a paso: (1) calcula la demanda proyectada de la ciudad y la resume,
 * (2) elige la cobertura objetivo, (3) los tipos de ruta y (4) confirma. La demanda se calcula una
 * sola vez al abrir y se entrega a quien llama para guardarla en memoria. La estimación de
 * cobertura planifica las rutas una vez (fuera del hilo de JavaFX) y lee de ahí la cobertura de
 * cada cantidad, así que mover el slider responde al instante.
 */
public final class LuisYDialogoAutogenerarRutas {

    private static final Logger LOG = Logger.getLogger(LuisYDialogoAutogenerarRutas.class.getName());
    private static final String ESTILO_ALERTA = "-fx-text-fill: #e53935; -fx-font-weight: bold;";
    private static final String ESTILO_OK = "-fx-text-fill: #2e9e5b; -fx-font-weight: bold;";
    private static final String ESTILO_PASO = "-fx-font-size: 14px; -fx-font-weight: bold;";
    /** Coberturas objetivo que ofrece el slider. */
    private static final int[] COBERTURAS = {50, 80, 100};

    /** Opciones elegidas, la semilla con la que se planificó (para generar las mismas rutas) y la demanda usada. */
    public record Seleccion(Opciones opciones, long semilla, EberthMDemandaCiudad demanda) {}

    private LuisYDialogoAutogenerarRutas() {}

    /**
     * @param alCalcularDemanda se llama (en el hilo de JavaFX) en cuanto la demanda del Paso 1 está
     *                          calculada, aunque luego se cancele el diálogo
     */
    public static Optional<Seleccion> mostrar(Consumer<Dialog<?>> estilizar, String nombreCiudad,
            EberthMParametrosCiudad ciudad, EberthMTipoDia dia, PieroGGrafoVial red,
            List<LuisYPuntoImportante> puntos, List<EberthMPoi> poisDemanda,
            List<EberthMServicioMicro> existentes, Consumer<EberthMDemandaCiudad> alCalcularDemanda) {
        long semilla = System.nanoTime();

        // ---- Paso 1: demanda
        Label lblDemanda = new Label("Calculando la demanda proyectada de la ciudad…");
        lblDemanda.setWrapText(true);
        lblDemanda.setStyle("-fx-font-weight: bold;");
        Label lblParametros = new Label(resumenCiudad(nombreCiudad, ciudad, dia));
        lblParametros.setWrapText(true);
        lblParametros.getStyleClass().add("texto-secundario");

        // ---- Paso 2: cobertura objetivo
        Slider objetivo = new Slider(0, COBERTURAS.length - 1, 1);
        objetivo.setMajorTickUnit(1);
        objetivo.setMinorTickCount(0);
        objetivo.setSnapToTicks(true);
        objetivo.setShowTickMarks(true);
        objetivo.setShowTickLabels(true);
        objetivo.setLabelFormatter(new StringConverter<>() {
            @Override public String toString(Double v) {
                return COBERTURAS[Math.clamp(Math.round(v), 0, COBERTURAS.length - 1)] + " %";
            }
            @Override public Double fromString(String s) {
                return 0.0;
            }
        });
        Label lblObjetivo = new Label();
        lblObjetivo.setStyle("-fx-font-weight: bold;");
        Label lblCobertura = new Label();
        lblCobertura.setWrapText(true);

        // ---- Paso 3: tipos de ruta
        CheckBox chkTroncales = new CheckBox("Troncales (80 %): de un extremo de la ciudad al otro, ≥ 8 km");
        chkTroncales.setSelected(true);
        CheckBox chkAlimentadoras = new CheckBox("Alimentadoras (20 %): de la periferia al centro, 4-6 km");
        chkAlimentadoras.setSelected(true);

        // ---- Paso 4: confirmación (y ajustes finos)
        Label lblResumenFinal = new Label();
        lblResumenFinal.setWrapText(true);
        lblResumenFinal.setStyle("-fx-font-weight: bold;");
        Label lblAdvertencia = new Label();
        lblAdvertencia.setWrapText(true);
        lblAdvertencia.setStyle(ESTILO_ALERTA);
        Label lblFlota = new Label();
        lblFlota.setWrapText(true);
        Spinner<Integer> rutas = spinner(1, EberthMGeneradorRutas.MAX_RUTAS_PLAN, 5);
        Spinner<Integer> paradas = spinner(3, 10, 5);
        Spinner<Integer> unidades = spinner(1, 150, 5);
        Spinner<Integer> frecuencia = spinner(5, 30, 10);
        Spinner<Integer> frecuenciaPunta = spinner(1, 10, 5);
        CheckBox autoRutas = new CheckBox("Calcular rutas necesarias para la cobertura objetivo");
        autoRutas.setSelected(true);
        CheckBox autoFlota = new CheckBox("Calcular la flota según la demanda y la longitud de cada ruta");
        autoFlota.setSelected(true);
        CheckBox autoFrecuencia = new CheckBox("Calcular automáticamente según la ciudad");
        autoFrecuencia.setSelected(true);
        Label lblFrecSugerida = new Label();
        lblFrecSugerida.getStyleClass().add("texto-secundario");

        AtomicReference<EberthMDemandaCiudad> demandaCalculada = new AtomicReference<>();
        AtomicReference<EberthMGeneradorRutas.Plan> plan = new AtomicReference<>();
        AtomicInteger token = new AtomicInteger();
        Node[] botonAceptar = new Node[1];
        ExecutorService hilo = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "plan-cobertura");
            t.setDaemon(true);
            return t;
        });
        boolean hayDemanda = ciudad != null && !poisDemanda.isEmpty();
        int existentesN = existentes.size();

        Runnable refrescar = () -> {
            int obj = COBERTURAS[Math.clamp(Math.round(objetivo.getValue()), 0, COBERTURAS.length - 1)];
            lblObjetivo.setText("Cobertura objetivo: " + obj + " %");
            rutas.setDisable(autoRutas.isSelected());
            TiposRuta tipos = tipos(chkTroncales, chkAlimentadoras);
            EberthMGeneradorRutas.Plan p = plan.get();
            int k = rutas.getValue();
            String nota = "";
            if (p != null && autoRutas.isSelected()) {
                int necesarias = p.rutasParaObjetivo(obj / 100.0);
                k = necesarias < 0 ? p.rutas().size() : necesarias;
                if (necesarias < 0) {
                    nota = String.format(Locale.ROOT, " No se alcanza el %d %%: el máximo posible con las calles "
                            + "y puntos cargados es %.0f %%.", obj, p.coberturaMaxima() * 100);
                }
                rutas.getValueFactory().setValue(Math.clamp(Math.max(1, k), 1, EberthMGeneradorRutas.MAX_RUTAS_PLAN));
            } else if (p != null) {
                k = Math.min(k, p.rutas().size());
            }
            int totalRutas = existentesN + Math.max(0, k);
            // Frecuencia sugerida por la ciudad; la flota se calcula por ruta al generar.
            int frecBase = Math.clamp(EberthMGeneradorRutas.frecuenciaPorCiudad(ciudad), 5, 30);
            int frecPunta = Math.clamp(EberthMServicioMicro.frecuenciaPuntaPorDefecto(frecBase), 1, 10);
            lblFrecSugerida.setText("Sugerido: cada " + frecBase + " min (hora punta: cada " + frecPunta + " min)");
            unidades.setDisable(autoFlota.isSelected());
            frecuencia.setDisable(autoFrecuencia.isSelected());
            frecuenciaPunta.setDisable(autoFrecuencia.isSelected());
            if (autoFrecuencia.isSelected()) {
                frecuencia.getValueFactory().setValue(frecBase);
                frecuenciaPunta.getValueFactory().setValue(Math.min(frecPunta, frecuencia.getValue()));
            }

            boolean listo = p != null || !hayDemanda;
            if (botonAceptar[0] != null) botonAceptar[0].setDisable(!listo);
            if (p == null) {
                lblCobertura.setStyle("");
                lblCobertura.setText(!hayDemanda
                        ? "Carga puntos de interés (colegios, hospitales, mercados…) para estimar la cobertura."
                        : demandaCalculada.get() == null ? "Esperando la demanda del Paso 1…"
                        : "Calculando cuántas rutas hacen falta…");
                lblAdvertencia.setText("");
                lblFlota.setText("");
                lblResumenFinal.setText("");
                return;
            }
            double cobertura = p.coberturaCon(k);
            int troncales = p.cuantas(k, TipoRuta.TRONCAL, tipos);
            int alimentadoras = p.cuantas(k, TipoRuta.ALIMENTADORA, tipos);
            lblCobertura.setText(String.format(Locale.ROOT, "Para ese objetivo hacen falta %d rutas nuevas "
                    + "(%d en total): se cubrirá el %.0f %% de la demanda.%s", k, totalRutas, cobertura * 100, nota));
            lblCobertura.setStyle(cobertura < 0.5 ? ESTILO_ALERTA : cobertura >= obj / 100.0 ? ESTILO_OK : "");
            lblResumenFinal.setText(String.format(Locale.ROOT, "Se generarán %d rutas (%d troncales y %d alimentadoras). "
                    + "Cobertura estimada: %.0f %%.", k, troncales, alimentadoras, cobertura * 100));
            lblAdvertencia.setText(cobertura < 0.5
                    ? "⚠️ Cobertura insuficiente. Aumenta las rutas o la frecuencia" : "");

            // La flota que calcula el generador para esas rutas frente a la demanda cubierta en hora punta.
            int flota = 0;
            for (int i = 0; i < Math.min(k, p.rutas().size()); i++) {
                flota += autoFlota.isSelected() ? p.rutas().get(i).unidadesDisponibles() : unidades.getValue();
            }
            double demandaPico = ciudad.demandaPicoSimuladaHora() * cobertura;
            lblFlota.setText(String.format(Locale.ROOT, "Flota estimada: %,d unidades para %d rutas "
                    + "(más unidades en las rutas más largas). Demanda cubierta en hora punta ≈ %,.0f pasajeros/h.",
                    flota, k, demandaPico));
            lblFlota.setStyle("");
        };

        // La planificación depende de la demanda, las paradas y los tipos: se rehace con retardo.
        Runnable replanificar = () -> {
            int mio = token.incrementAndGet();
            plan.set(null);
            refrescar.run();
            EberthMDemandaCiudad demanda = demandaCalculada.get();
            if (!hayDemanda || demanda == null) return;
            int paradasN = paradas.getValue();
            TiposRuta tipos = tipos(chkTroncales, chkAlimentadoras);
            hilo.execute(() -> {
                EberthMGeneradorRutas.Plan nuevo = null;
                try {
                    EberthMGeneradorRutas generador = new EberthMGeneradorRutas(red, puntos, ciudad, new Random(semilla));
                    generador.setCancelacion(() -> token.get() != mio);
                    nuevo = generador.planificar(demanda, existentes, new Opciones(1, paradasN, 0, 10, 5, 0, tipos),
                            EberthMGeneradorRutas.MAX_RUTAS_PLAN);
                } catch (RuntimeException ex) {
                    LOG.log(Level.FINE, "No se pudo estimar la cobertura", ex);
                }
                EberthMGeneradorRutas.Plan resultado = nuevo;
                Platform.runLater(() -> {
                    if (token.get() != mio) return;
                    plan.set(resultado);
                    if (resultado == null || resultado.rutas().isEmpty()) {
                        lblCobertura.setStyle(ESTILO_ALERTA);
                        lblCobertura.setText("No se pudo trazar ninguna ruta con los datos cargados: faltan calles "
                                + "o POIs lo bastante lejanos (las rutas deben medir al menos 4 km).");
                    } else {
                        refrescar.run();
                    }
                });
            });
        };
        PauseTransition retardo = new PauseTransition(Duration.millis(450));
        retardo.setOnFinished(e -> replanificar.run());
        paradas.valueProperty().addListener((o, a, b) -> retardo.playFromStart());
        for (CheckBox tipo : List.of(chkTroncales, chkAlimentadoras)) {
            CheckBox otro = tipo == chkTroncales ? chkAlimentadoras : chkTroncales;
            tipo.selectedProperty().addListener((o, antes, ahora) -> {
                if (!ahora && !otro.isSelected()) tipo.setSelected(true); // al menos un tipo
                else retardo.playFromStart();
            });
        }

        objetivo.valueProperty().addListener((o, a, b) -> refrescar.run());
        rutas.valueProperty().addListener((o, a, b) -> refrescar.run());
        unidades.valueProperty().addListener((o, a, b) -> refrescar.run());
        for (CheckBox c : List.of(autoRutas, autoFlota, autoFrecuencia)) {
            c.selectedProperty().addListener((o, a, b) -> refrescar.run());
        }

        GridPane ajustes = new GridPane();
        ajustes.setHgap(10);
        ajustes.setVgap(8);
        ColumnConstraints etiquetas = new ColumnConstraints();
        ColumnConstraints campos = new ColumnConstraints();
        campos.setHgrow(Priority.ALWAYS);
        ajustes.getColumnConstraints().addAll(etiquetas, campos);
        int f = 0;
        ajustes.add(autoRutas, 0, f++, 2, 1);
        ajustes.addRow(f++, new Label("Cantidad de rutas nuevas (1-" + EberthMGeneradorRutas.MAX_RUTAS_PLAN + ")"), rutas);
        ajustes.addRow(f++, new Label("Paradas promedio por ruta (3-10)"), paradas);
        ajustes.add(autoFlota, 0, f++, 2, 1);
        ajustes.addRow(f++, new Label("Unidades por ruta (1-150)"), unidades);
        ajustes.add(autoFrecuencia, 0, f++, 2, 1);
        ajustes.addRow(f++, new Label("Frecuencia hora normal (5-30 min)"), frecuencia);
        ajustes.addRow(f++, new Label("Frecuencia hora punta (1-10 min)"), frecuenciaPunta);
        ajustes.add(lblFrecSugerida, 1, f++);
        TitledPane avanzado = new TitledPane("Ajustes avanzados", ajustes);
        avanzado.setExpanded(false);

        VBox contenido = new VBox(10,
                paso("Paso 1 · Demanda de la ciudad"), lblDemanda, lblParametros,
                new Separator(),
                paso("Paso 2 · Cobertura objetivo"), objetivo, lblObjetivo, lblCobertura,
                new Separator(),
                paso("Paso 3 · Tipos de rutas"), chkTroncales, chkAlimentadoras,
                new Separator(),
                paso("Paso 4 · Confirmación"), lblResumenFinal, lblAdvertencia, lblFlota, avanzado);
        contenido.setPadding(new Insets(8));
        contenido.setPrefWidth(600);
        ScrollPane desplazable = new ScrollPane(contenido);
        desplazable.setFitToWidth(true);
        desplazable.setPrefViewportHeight(560);
        desplazable.setStyle("-fx-background-color: transparent;");

        Dialog<Seleccion> dialogo = new Dialog<>();
        estilizar.accept(dialogo);
        dialogo.setTitle("Autogenerar rutas");
        dialogo.setHeaderText("Planificación de rutas según la demanda de la ciudad");
        dialogo.getDialogPane().setContent(desplazable);
        dialogo.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        botonAceptar[0] = dialogo.getDialogPane().lookupButton(ButtonType.OK);
        dialogo.setResultConverter(b -> {
            if (b != ButtonType.OK) return null;
            for (Spinner<Integer> sp : List.of(rutas, paradas, unidades, frecuencia, frecuenciaPunta)) confirmar(sp);
            int frec = frecuencia.getValue();
            return new Seleccion(new Opciones(Math.max(1, rutas.getValue()), paradas.getValue(),
                    autoFlota.isSelected() ? 0 : unidades.getValue(), frec,
                    Math.min(frecuenciaPunta.getValue(), frec), 0, tipos(chkTroncales, chkAlimentadoras)),
                    semilla, demandaCalculada.get());
        });

        refrescar.run();
        if (hayDemanda) {
            // Paso 1: la demanda se calcula al abrir y queda en memoria para el generador.
            hilo.execute(() -> {
                EberthMDemandaCiudad demanda = null;
                String error = null;
                try {
                    demanda = EberthMDemandaCiudad.generar(red, poisDemanda, ciudad, dia);
                } catch (RuntimeException ex) {
                    LOG.log(Level.FINE, "No se pudo calcular la demanda", ex);
                    error = ex.getMessage();
                }
                EberthMDemandaCiudad resultado = demanda;
                String fallo = error;
                Platform.runLater(() -> {
                    if (resultado == null) {
                        lblDemanda.setStyle(ESTILO_ALERTA);
                        lblDemanda.setText("No se pudo calcular la demanda" + (fallo == null ? "." : ": " + fallo));
                        return;
                    }
                    demandaCalculada.set(resultado);
                    lblDemanda.setText(resumenDemanda(nombreCiudad, resultado));
                    alCalcularDemanda.accept(resultado);
                    replanificar.run();
                });
            });
        } else {
            lblDemanda.setText("Sin parámetros de ciudad o sin puntos de interés: no hay demanda que estimar.");
        }
        try {
            return dialogo.showAndWait();
        } finally {
            token.incrementAndGet();
            retardo.stop();
            hilo.shutdownNow();
        }
    }

    private static TiposRuta tipos(CheckBox troncales, CheckBox alimentadoras) {
        if (troncales.isSelected() && alimentadoras.isSelected()) return TiposRuta.AMBAS;
        return alimentadoras.isSelected() ? TiposRuta.ALIMENTADORAS : TiposRuta.TRONCALES;
    }

    private static Label paso(String texto) {
        Label l = new Label(texto);
        l.setStyle(ESTILO_PASO);
        return l;
    }

    /** «Juliaca: 182,000 viajes/día. Pico: 30,000 a las 8 AM». */
    static String resumenDemanda(String ciudad, EberthMDemandaCiudad demanda) {
        int hora = demanda.horaPico();
        return String.format(Locale.ROOT, "%s: %,d viajes/día. Pico: %,d a las %d %s",
                ciudad, demanda.totalViajes(), demanda.viajesPico(), hora % 12 == 0 ? 12 : hora % 12,
                hora < 12 ? "AM" : "PM");
    }

    private static String resumenCiudad(String nombre, EberthMParametrosCiudad ciudad, EberthMTipoDia dia) {
        if (ciudad == null) return nombre + ": sin parámetros de ciudad (se usan valores neutros).";
        double pct = ciudad.getPorcentajeTransportePublico();
        String pctTexto = pct == Math.rint(pct) ? String.format(Locale.ROOT, "%.0f", pct)
                : String.format(Locale.ROOT, "%.1f", pct);
        return String.format(Locale.ROOT, "%,d hab. | %s%% usa transporte público | horas punta: %s | %s",
                ciudad.getPoblacionTotal(), pctTexto, ciudad.getHorasPunta(), dia);
    }

    private static Spinner<Integer> spinner(int min, int max, int valor) {
        Spinner<Integer> sp = new Spinner<>(min, max, valor);
        sp.setEditable(true);
        sp.setMaxWidth(Double.MAX_VALUE);
        sp.getEditor().focusedProperty().addListener((o, antes, ahora) -> {
            if (!ahora) confirmar(sp);
        });
        sp.getEditor().setOnAction(e -> confirmar(sp));
        return sp;
    }

    private static void confirmar(Spinner<Integer> sp) {
        try {
            sp.getValueFactory().setValue(Integer.parseInt(sp.getEditor().getText().trim()));
        } catch (NumberFormatException ex) {
            sp.getEditor().setText(String.valueOf(sp.getValue()));
        }
    }
}
