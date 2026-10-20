package trafiqueishon.ui;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Selector de archivos hecho solo con JavaFX. Reemplaza al {@code FileChooser} nativo, que en algunos
 * Linux (GTK) deja los archivos deshabilitados y solo deja entrar en carpetas.
 */
public final class LuisYSelectorArchivos {

    /** Filtro de nombres: {@code "*.json"}, {@code "*.xlsx"}… o {@code "*.*"} para todo. */
    public record Filtro(String descripcion, List<String> patrones) {
        public Filtro(String descripcion, String... patrones) {
            this(descripcion, List.of(patrones));
        }

        boolean acepta(String nombre) {
            String minuscula = nombre.toLowerCase(Locale.ROOT);
            for (String patron : patrones) {
                String p = patron.toLowerCase(Locale.ROOT);
                if (p.equals("*.*") || p.equals("*")) return true;
                if (p.startsWith("*.") && minuscula.endsWith(p.substring(1))) return true;
            }
            return false;
        }
    }

    private static File ultimaCarpeta;

    private final Dialog<File> dialogo = new Dialog<>();
    private final TextField campoRuta = new TextField();
    private final TextField campoNombre = new TextField();
    private final ListView<File> lista = new ListView<>();
    private final ComboBox<Filtro> selectorFiltro = new ComboBox<>();
    private final boolean guardar;
    private File carpeta;

    private LuisYSelectorArchivos(String titulo, boolean guardar, List<Filtro> filtros,
                                  File carpetaInicial, String nombreInicial, Consumer<Dialog<?>> estilo) {
        this.guardar = guardar;
        dialogo.setTitle(titulo);
        dialogo.setHeaderText(null);
        dialogo.setResizable(true);
        if (estilo != null) estilo.accept(dialogo);

        Button subir = new Button("↑");
        subir.setOnAction(e -> {
            File padre = carpeta.getParentFile();
            if (padre != null) irA(padre);
        });
        Button inicio = new Button("🏠");
        inicio.setOnAction(e -> irA(new File(System.getProperty("user.home"))));
        campoRuta.setOnAction(e -> {
            File destino = new File(campoRuta.getText().trim());
            if (destino.isDirectory()) irA(destino);
            else campoRuta.setText(carpeta.getAbsolutePath());
        });
        HBox.setHgrow(campoRuta, Priority.ALWAYS);
        HBox barra = new HBox(6, subir, inicio, campoRuta);
        barra.setAlignment(Pos.CENTER_LEFT);

        lista.setPrefSize(560, 340);
        lista.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(File f, boolean vacio) {
                super.updateItem(f, vacio);
                setText(vacio || f == null ? null : (f.isDirectory() ? "📁 " : "📄 ") + f.getName());
            }
        });
        lista.getSelectionModel().selectedItemProperty().addListener((o, antes, f) -> {
            if (f != null && f.isFile()) campoNombre.setText(f.getName());
        });
        lista.setOnMouseClicked(e -> {
            File f = lista.getSelectionModel().getSelectedItem();
            if (e.getButton() != MouseButton.PRIMARY || e.getClickCount() < 2 || f == null) return;
            if (f.isDirectory()) irA(f);
            else dialogo.setResult(resultado());
        });

        selectorFiltro.getItems().setAll(filtros);
        selectorFiltro.getSelectionModel().selectFirst();
        selectorFiltro.setMaxWidth(Double.MAX_VALUE);
        selectorFiltro.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Filtro f) { return f == null ? "" : f.descripcion(); }
            @Override public Filtro fromString(String s) { return null; }
        });
        selectorFiltro.valueProperty().addListener((o, antes, f) -> irA(carpeta));

        campoNombre.setPromptText(guardar ? "Nombre del archivo" : "Archivo seleccionado");
        campoNombre.setEditable(guardar);
        if (nombreInicial != null) campoNombre.setText(nombreInicial);
        HBox.setHgrow(campoNombre, Priority.ALWAYS);
        HBox.setHgrow(selectorFiltro, Priority.ALWAYS);
        HBox pie = new HBox(8, new Label("Nombre"), campoNombre, selectorFiltro);
        pie.setAlignment(Pos.CENTER_LEFT);

        VBox contenido = new VBox(8, barra, lista, pie);
        contenido.setPadding(new Insets(4));
        dialogo.getDialogPane().setContent(contenido);
        ButtonType aceptar = new ButtonType(guardar ? "Guardar" : "Abrir", ButtonBar.ButtonData.OK_DONE);
        dialogo.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, aceptar);
        dialogo.getDialogPane().lookupButton(aceptar).disableProperty()
                .bind(campoNombre.textProperty().isEmpty());
        dialogo.setResultConverter(b -> b == aceptar ? resultado() : null);

        File inicial = carpetaInicial != null && carpetaInicial.isDirectory() ? carpetaInicial
                : ultimaCarpeta != null && ultimaCarpeta.isDirectory() ? ultimaCarpeta
                : new File(System.getProperty("user.home"));
        irA(inicial);
    }

    private File resultado() {
        String nombre = campoNombre.getText().trim();
        if (nombre.isEmpty()) return null;
        File f = new File(nombre).isAbsolute() ? new File(nombre) : new File(carpeta, nombre);
        if (!guardar && !f.isFile()) return null;
        if (f.getParentFile() != null) ultimaCarpeta = f.getParentFile();
        return f;
    }

    private void irA(File destino) {
        carpeta = destino;
        campoRuta.setText(destino.getAbsolutePath());
        Filtro filtro = selectorFiltro.getValue();
        File[] hijos = destino.listFiles();
        List<File> visibles = new ArrayList<>();
        if (hijos != null) {
            for (File f : hijos) {
                if (f.isHidden() || f.getName().startsWith(".")) continue;
                if (f.isDirectory() || filtro == null || filtro.acepta(f.getName())) visibles.add(f);
            }
        }
        visibles.sort(Comparator.comparing((File f) -> !f.isDirectory())
                .thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        lista.setItems(FXCollections.observableArrayList(visibles));
    }

    public static File abrir(Window dueno, String titulo, List<Filtro> filtros, File carpeta,
                             Consumer<Dialog<?>> estilo) {
        return mostrar(dueno, new LuisYSelectorArchivos(titulo, false, filtros, carpeta, null, estilo));
    }

    public static File guardar(Window dueno, String titulo, List<Filtro> filtros, File carpeta,
                               String nombreInicial, Consumer<Dialog<?>> estilo) {
        return mostrar(dueno, new LuisYSelectorArchivos(titulo, true, filtros, carpeta, nombreInicial, estilo));
    }

    private static File mostrar(Window dueno, LuisYSelectorArchivos selector) {
        if (dueno != null) selector.dialogo.initOwner(dueno);
        return selector.dialogo.showAndWait().orElse(null);
    }

    /** Divide un patrón como {@code "*.geojson;*.json"} en filtro de una sola entrada. */
    public static Filtro filtro(String descripcion, String patrones) {
        return new Filtro(descripcion, Arrays.asList(patrones.split(";")));
    }
}
