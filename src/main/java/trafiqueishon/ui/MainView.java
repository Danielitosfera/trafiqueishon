package trafiqueishon.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class MainView {

    private final BorderPane root;

    public MainView() {
        root = new BorderPane();
        root.getStyleClass().add("root-view");
        root.setCenter(new MapaCanvas());
        root.setRight(crearPanelControl());
        root.setBottom(crearStatusBar());
    }

    private TabPane crearPanelControl() {
        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("panel-control");
        tabs.setPrefWidth(340);
        tabs.setMinWidth(280);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        tabs.getTabs().addAll(
            crearTab("Empresas", "Empresas de transporte publico"),
            crearTab("Rutas", "Gestion de rutas urbanas"),
            crearTab("Demanda", "Generacion de viajes y pasajeros"),
            crearTab("Metricas", "Estadisticas en vivo"),
            crearTab("Escenario", "Configuracion del escenario")
        );
        return tabs;
    }

    private Tab crearTab(String titulo, String descripcion) {
        VBox cont = new VBox(10);
        cont.setPadding(new Insets(15));
        cont.getStyleClass().add("tab-contenido");

        Label t = new Label(titulo);
        t.getStyleClass().add("tab-titulo");

        Label d = new Label(descripcion);
        d.getStyleClass().add("tab-descripcion");
        d.setWrapText(true);

        cont.getChildren().addAll(t, d);
        return new Tab(titulo, cont);
    }

    private HBox crearStatusBar() {
        HBox bar = new HBox(20);
        bar.setPadding(new Insets(6, 15, 6, 15));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");

        bar.getChildren().addAll(
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

    public BorderPane getRoot() { return root; }
}
