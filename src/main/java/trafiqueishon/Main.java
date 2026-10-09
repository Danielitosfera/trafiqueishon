package trafiqueishon;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import trafiqueishon.ui.MainView;

public class Main extends Application {

    @Override
    public void start(Stage stage) {
        MainView view = new MainView();
        Scene scene = new Scene(view.getRoot(), 1280, 800);

        var css = getClass().getResource("/styles.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        } else {
            System.err.println("[Main] No encontre styles.css en resources");
        }

        stage.setTitle("Trafiqueishon - Juliaca");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}