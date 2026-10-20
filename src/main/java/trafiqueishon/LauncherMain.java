package trafiqueishon;

import javafx.application.Application;

/**
 * Punto de entrada del jar. No extiende {@link Application}: así el jar sin módulos
 * (fat jar) arranca en Linux y Windows sin pedir el módulo de JavaFX en el module-path.
 */
public final class LauncherMain {

    private LauncherMain() {}

    public static void main(String[] args) {
        Application.launch(LuisYMain.class, args);
    }
}
