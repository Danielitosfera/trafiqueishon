package trafiqueishon;

import trafiqueishon.ui.MainView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class Main extends Application {

    @Override
    public void start(Stage stage) {
        MainView view = new MainView();
        Scene scene = new Scene(view.getRoot(), 1400, 850);
        scene.getStylesheets().add(
                getClass().getResource("/estilos/estilo.css").toExternalForm()
        );
        stage.setTitle("Trafiqueishon - Simulador de Trafico (Juliaca)");
        stage.setScene(scene);
        stage.setMinWidth(1000);
        stage.setMinHeight(600);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}