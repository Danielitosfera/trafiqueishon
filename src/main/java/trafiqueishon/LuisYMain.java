package trafiqueishon;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import trafiqueishon.ui.LuisYVentanaPrincipal;

public class LuisYMain extends Application {

    @Override
    public void start(Stage stage) {
        LuisYVentanaPrincipal ventana = new LuisYVentanaPrincipal();
        Scene scene = new Scene(ventana.getRoot(), 1200, 760);

        var css = getClass().getResource("/estilos/estilo.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }

        stage.setTitle("Trafiqueishon - Simulador de Buses");
        stage.setScene(scene);
        stage.setMinWidth(980);
        stage.setMinHeight(640);
        stage.setOnCloseRequest(e -> ventana.cerrar());
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}