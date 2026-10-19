package trafiqueishon.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ListView;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.ruta.LuisYTrayecto;
import trafiqueishon.simulacion.EberthMServicioMicro;

import java.util.List;

/**
 * Panel desplegable con los paraderos de UNA micro: "Recorrido de partida" y
 * "Recorrido de retorno", cada uno en una lista colapsable con scroll. Está oculto
 * ({@code visible} y {@code managed} en falso) hasta que se llama a
 * {@link #mostrar(EberthMServicioMicro)}.
 */
public class LuisYPanelParadas extends VBox {

    private static final double ANCHO = 290;
    private static final double ALTO_FILA = 28;
    private static final double ALTO_MAXIMO_LISTA = 180;

    private final TitledPane contenedor = new TitledPane();
    private final ListView<String> listaPartida = new ListView<>();
    private final ListView<String> listaRetorno = new ListView<>();

    public LuisYPanelParadas() {
        TitledPane partida = new TitledPane("Partida · ida (azul)", listaPartida);
        TitledPane retorno = new TitledPane("Retorno · vuelta (roja)", listaRetorno);
        partida.setExpanded(true);
        retorno.setExpanded(false);
        VBox listas = new VBox(4, partida, retorno);
        listas.setPadding(new Insets(4));
        contenedor.setContent(listas);
        contenedor.setExpanded(true);
        getChildren().add(contenedor);

        setPrefWidth(ANCHO);
        setMaxWidth(ANCHO);
        setMaxHeight(Region.USE_PREF_SIZE);
        ocultar();
    }

    /** Muestra los paraderos de la micro indicada. Llamar desde el hilo de JavaFX. */
    public void mostrar(EberthMServicioMicro servicio) {
        contenedor.setText("Paraderos · " + servicio.nombre());
        llenar(listaPartida, servicio.trayecto());
        if (servicio.tieneRetorno()) llenar(listaRetorno, servicio.trayectoVuelta());
        else listaRetorno.getItems().setAll("Sin retorno");
        setVisible(true);
        setManaged(true);
    }

    /** Oculta el panel (0 o más de 1 micro seleccionada). */
    public void ocultar() {
        setVisible(false);
        setManaged(false);
    }

    private static void llenar(ListView<String> lista, LuisYTrayecto trayecto) {
        List<String> nombres = trayecto.puntos().stream().map(LuisYPuntoImportante::nombre).toList();
        lista.getItems().setAll(nombres);
        lista.setPrefHeight(Math.min(ALTO_MAXIMO_LISTA, nombres.size() * ALTO_FILA + 2));
        lista.setMaxHeight(ALTO_MAXIMO_LISTA);
    }
}
