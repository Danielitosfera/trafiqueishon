package trafiqueishon.ui;

import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.simulacion.EberthMGestorServicios;
import trafiqueishon.simulacion.EberthMServicioMicro;
import trafiqueishon.simulacion.EberthMTipoRetorno;
import trafiqueishon.simulacion.EberthMSimuladorBuses;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Formulario modal para crear o editar una ruta de micro. Solo recoge datos: no calcula nada ni
 * valida contra otras rutas; quien lo llama recibe un {@link Datos} y decide qué hacer.
 */
final class LuisYDialogoRutaMicro {

    /** Valores introducidos por el usuario, sin validar. */
    record Datos(String nombre, List<LuisYPuntoImportante> paraderos, boolean optimizarOrden,
                 EberthMTipoRetorno retorno, List<LuisYPuntoImportante> vueltaManual,
                 int frecuenciaMin, int unidades, String colorHex) {}

    static final int MAX_UNIDADES_RUTA = 500;
    static final int MAX_FRECUENCIA_MIN = 120;
    static final int MIN_PASAJEROS = 10;

    private LuisYDialogoRutaMicro() {}

    /**
     * @param estilizar          aplica el tema de la ventana al diálogo
     * @param alError            muestra un error (título, mensaje)
     * @return los datos si el usuario pulsó «Guardar ruta»; vacío si canceló o no hay flota libre
     */
    static Optional<Datos> mostrar(Consumer<Dialog<?>> estilizar, BiConsumer<String, String> alError,
            List<LuisYPuntoImportante> puntos, List<EberthMServicioMicro> rutas,
            EberthMServicioMicro existente) {
        Dialog<Boolean> dialogo = new Dialog<>();
        estilizar.accept(dialogo);
        dialogo.setTitle(existente == null ? "Crear ruta de micro" : "Editar ruta de micro");
        dialogo.setHeaderText("Configura la línea, sus paraderos, frecuencia y color.");
        ButtonType guardar = new ButtonType(existente == null ? "Guardar ruta" : "Guardar cambios",
                ButtonBar.ButtonData.OK_DONE);
        dialogo.getDialogPane().getButtonTypes().addAll(guardar, ButtonType.CANCEL);

        TextField nombre = new TextField(existente == null
                ? "Micro " + (rutas.size() + 1) : existente.nombre());
        Spinner<Integer> frecuencia = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                1, MAX_FRECUENCIA_MIN, Math.min(MAX_FRECUENCIA_MIN,
                        existente == null ? 10 : existente.frecuenciaMinutos())));
        long unidadesExistentes = rutas.stream()
                .filter(ruta -> existente == null || !ruta.id().equals(existente.id()))
                .mapToLong(EberthMServicioMicro::unidadesDisponibles).sum();
        // Tope por ruta y, además, lo que quede de la flota total del simulador.
        int maxUnidades = (int) Math.max(1,
                Math.min(MAX_UNIDADES_RUTA, EberthMSimuladorBuses.MAX_UNIDADES - unidadesExistentes));
        if (maxUnidades == 1 && existente == null
                && unidadesExistentes >= EberthMSimuladorBuses.MAX_UNIDADES) {
            alError.accept("Capacidad de flota alcanzada",
                    "La flota configurada ya suma 6.000 unidades.");
            return Optional.empty();
        }
        int unidadesIniciales = Math.min(maxUnidades, existente == null ? 4
                : existente.unidadesDisponibles());
        Spinner<Integer> unidades = new Spinner<>(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, maxUnidades, unidadesIniciales));
        unidades.setPrefWidth(125);
        for (Spinner<Integer> campo : List.of(frecuencia, unidades)) {
            campo.setEditable(true);
            campo.focusedProperty().addListener((o, antes, ahora) -> {
                if (!ahora) confirmarTexto(campo);
            });
        }
        ColorPicker color = new ColorPicker(Color.web(existente == null
                ? EberthMGestorServicios.siguienteColor(rutas) : existente.color()));
        CheckBox optimizarOrden = new CheckBox("Optimizar el orden por tiempo de viaje");
        optimizarOrden.setSelected(existente == null);

        TextField filtro = new TextField();
        filtro.setPromptText("Filtrar ubicaciones…");
        FilteredList<LuisYPuntoImportante> disponiblesFiltrados =
                new FilteredList<>(FXCollections.observableArrayList(puntos));
        filtro.textProperty().addListener((o, a, texto) -> {
            String buscado = texto == null ? "" : texto.trim().toLowerCase(Locale.ROOT);
            disponiblesFiltrados.setPredicate(p -> buscado.isEmpty()
                    || p.nombre().toLowerCase(Locale.ROOT).contains(buscado));
        });
        java.text.Collator orden = java.text.Collator.getInstance(Locale.forLanguageTag("es"));
        SortedList<LuisYPuntoImportante> disponiblesOrdenados = new SortedList<>(disponiblesFiltrados,
                (a, b) -> orden.compare(a.nombre(), b.nombre()));
        ListView<LuisYPuntoImportante> disponibles = new ListView<>(disponiblesOrdenados);
        disponibles.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        disponibles.setPrefHeight(230);
        ListView<LuisYPuntoImportante> secuencia = new ListView<>(
                FXCollections.observableArrayList(
                        existente == null ? List.of() : existente.trayecto().puntos()));
        // Doble clic sobre una ubicación: pasa a «Paraderos en orden».
        disponibles.setCellFactory(list -> {
            ListCell<LuisYPuntoImportante> celda = new ListCell<>() {
                @Override protected void updateItem(LuisYPuntoImportante p, boolean vacio) {
                    super.updateItem(p, vacio);
                    setText(vacio || p == null ? null : p.nombre());
                }
            };
            celda.setOnMouseClicked(ev -> {
                LuisYPuntoImportante p = celda.getItem();
                if (ev.getClickCount() == 2 && !celda.isEmpty() && p != null
                        && !secuencia.getItems().contains(p)) {
                    secuencia.getItems().add(p);
                }
            });
            return celda;
        });

        secuencia.setPrefHeight(230);

        // Doble clic en la lista de la derecha: quita el paradero.
        secuencia.setCellFactory(list -> celdaReordenable(secuencia, true, optimizarOrden.selectedProperty()));
        Button agregar = new Button("Añadir →");
        agregar.setMaxWidth(Double.MAX_VALUE);
        agregar.setOnAction(e -> {
            for (LuisYPuntoImportante p : new ArrayList<>(disponibles.getSelectionModel().getSelectedItems())) {
                if (p != null && !secuencia.getItems().contains(p)) secuencia.getItems().add(p);
            }
        });

        ComboBox<EberthMTipoRetorno> tipoRetorno = new ComboBox<>();
        tipoRetorno.getItems().setAll(EberthMTipoRetorno.values());
        tipoRetorno.setValue(existente == null ? EberthMTipoRetorno.INVERSO : existente.retorno());
        ListView<LuisYPuntoImportante> secuenciaVuelta = new ListView<>(
                FXCollections.observableArrayList(existente == null
                        ? List.of() : existente.trayectoVuelta().puntos()));
        secuenciaVuelta.setPrefHeight(150);
        secuenciaVuelta.setCellFactory(list -> celdaReordenable(secuenciaVuelta, true, null));
        Button agregarVuelta = new Button("Añadir a la vuelta →");
        agregarVuelta.setMaxWidth(Double.MAX_VALUE);
        agregarVuelta.setOnAction(e -> {
            for (LuisYPuntoImportante p : new ArrayList<>(disponibles.getSelectionModel().getSelectedItems())) {
                if (p != null && !secuenciaVuelta.getItems().contains(p)) secuenciaVuelta.getItems().add(p);
            }
        });
        VBox seccionVuelta = new VBox(5,
                new Label("Paraderos de vuelta · solo se editan con «Retorno Manual»"),
                secuenciaVuelta, agregarVuelta);
        // Inverso: la lista de vuelta es la de ida al revés y no se edita. Sin retorno: vacía.
        // Manual: queda libre; si estaba vacía parte de la inversa como ayuda.
        Runnable sincronizarVuelta = () -> {
            EberthMTipoRetorno tipo = tipoRetorno.getValue();
            boolean manual = tipo == EberthMTipoRetorno.MANUAL;
            secuenciaVuelta.setDisable(!manual);
            agregarVuelta.setDisable(!manual);
            List<LuisYPuntoImportante> inversa = new ArrayList<>(secuencia.getItems());
            Collections.reverse(inversa);
            if (tipo == EberthMTipoRetorno.INVERSO) {
                secuenciaVuelta.getItems().setAll(inversa);
            } else if (tipo == EberthMTipoRetorno.SIN_RETORNO) {
                secuenciaVuelta.getItems().clear();
            } else if (secuenciaVuelta.getItems().isEmpty()) {
                secuenciaVuelta.getItems().setAll(inversa);
            }
        };
        tipoRetorno.valueProperty().addListener((o, antes, ahora) -> sincronizarVuelta.run());
        secuencia.getItems().addListener((javafx.collections.ListChangeListener<LuisYPuntoImportante>) c -> {
            if (tipoRetorno.getValue() == EberthMTipoRetorno.INVERSO) sincronizarVuelta.run();
        });
        // Al editar una ruta manual se conservan sus paraderos de vuelta guardados.
        sincronizarVuelta.run();

        // Dos paneles: buscar y elegir a la izquierda, paraderos de ida a la derecha.
        VBox listaDisponibles = new VBox(5, new Label("Ubicaciones disponibles · doble clic para añadir"),
                filtro, disponibles, agregar);
        VBox listaSecuencia = new VBox(5, new Label("Paraderos de ida · arrastra para ordenar, doble clic quita"),
                secuencia);
        VBox.setVgrow(disponibles, Priority.ALWAYS);
        VBox.setVgrow(secuencia, Priority.ALWAYS);
        HBox selectorParaderos = new HBox(10, listaDisponibles, listaSecuencia);
        HBox.setHgrow(listaDisponibles, Priority.ALWAYS);
        HBox.setHgrow(listaSecuencia, Priority.ALWAYS);
        listaDisponibles.setPrefWidth(300);
        listaSecuencia.setPrefWidth(300);

        GridPane contenido = new GridPane();
        contenido.setHgap(8);
        contenido.setVgap(8);
        contenido.addRow(0, new Label("Nombre"), nombre);
        contenido.addRow(1, new Label("Frecuencia (min)"), frecuencia);
        contenido.addRow(2, new Label("Unidades"), unidades);
        contenido.addRow(3, new Label("Color único"), color);
        contenido.add(new Label("Incluye y ordena los paraderos (mínimo 2)"), 0, 4, 2, 1);
        contenido.add(selectorParaderos, 0, 5, 2, 1);
        contenido.addRow(6, new Label("Tipo de Retorno"), tipoRetorno);
        contenido.add(seccionVuelta, 0, 7, 2, 1);
        contenido.add(optimizarOrden, 0, 8, 2, 1);
        dialogo.getDialogPane().setContent(contenido);
        dialogo.setResultConverter(b -> b == guardar);

        Optional<Boolean> confirmado = dialogo.showAndWait();
        if (confirmado.isEmpty() || !confirmado.get()) return Optional.empty();
        // Un valor tecleado y no confirmado con Enter no habría llegado al spinner.
        for (Spinner<Integer> campo : List.of(frecuencia, unidades)) confirmarTexto(campo);
        return Optional.of(new Datos(nombre.getText().trim(), List.copyOf(secuencia.getItems()),
                optimizarOrden.isSelected(), tipoRetorno.getValue(),
                List.copyOf(secuenciaVuelta.getItems()), frecuencia.getValue(), unidades.getValue(),
                colorHex(color.getValue())));
    }

    /** Pasa al valor del spinner el texto escrito en su editor; si no es válido lo descarta. */
    private static void confirmarTexto(Spinner<Integer> campo) {
        try {
            campo.increment(0);
        } catch (RuntimeException ex) {
            campo.getEditor().setText(String.valueOf(campo.getValue()));
        }
    }

    private static String colorHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }

    /**
     * Celda de lista que se reordena arrastrando y suelta un elemento con doble clic
     * (lo quita de la lista). {@code reordenarDeshabilitado}, si no es null y vale true,
     * bloquea el arrastre (p. ej. cuando el orden lo decide el algoritmo).
     */
    private static ListCell<LuisYPuntoImportante> celdaReordenable(ListView<LuisYPuntoImportante> lista,
            boolean numerar, javafx.beans.value.ObservableBooleanValue reordenarDeshabilitado) {
        ListCell<LuisYPuntoImportante> celda = new ListCell<>() {
            @Override protected void updateItem(LuisYPuntoImportante p, boolean vacio) {
                super.updateItem(p, vacio);
                setText(vacio || p == null ? null
                        : (numerar ? (getIndex() + 1) + ". " : "") + p.nombre());
            }
        };
        celda.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2 && !celda.isEmpty() && celda.getItem() != null) {
                lista.getItems().remove(celda.getItem());
            }
        });
        celda.setOnDragDetected(ev -> {
            if (celda.isEmpty() || (reordenarDeshabilitado != null && reordenarDeshabilitado.get())) return;
            javafx.scene.input.Dragboard db = celda.startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
            javafx.scene.input.ClipboardContent contenido = new javafx.scene.input.ClipboardContent();
            contenido.putString(Integer.toString(celda.getIndex()));
            db.setContent(contenido);
            ev.consume();
        });
        celda.setOnDragOver(ev -> {
            if (ev.getGestureSource() != celda && ev.getDragboard().hasString()
                    && ev.getGestureSource() instanceof ListCell<?> origen && origen.getListView() == lista) {
                ev.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
            }
            ev.consume();
        });
        celda.setOnDragEntered(ev -> {
            if (ev.getGestureSource() != celda && ev.getGestureSource() instanceof ListCell<?> o
                    && o.getListView() == lista) celda.setOpacity(0.5);
        });
        celda.setOnDragExited(ev -> celda.setOpacity(1));
        celda.setOnDragDropped(ev -> {
            boolean hecho = false;
            if (ev.getDragboard().hasString()) {
                int desde = Integer.parseInt(ev.getDragboard().getString());
                int hasta = celda.isEmpty() ? lista.getItems().size() - 1 : celda.getIndex();
                if (desde >= 0 && desde < lista.getItems().size() && hasta >= 0 && desde != hasta) {
                    LuisYPuntoImportante movido = lista.getItems().remove(desde);
                    lista.getItems().add(hasta, movido);
                    lista.getSelectionModel().select(hasta);
                    hecho = true;
                }
            }
            ev.setDropCompleted(hecho);
            ev.consume();
        });
        celda.setOnDragDone(javafx.scene.input.DragEvent::consume);
        return celda;
    }
}
