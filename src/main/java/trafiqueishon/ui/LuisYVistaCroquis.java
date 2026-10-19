package trafiqueishon.ui;

import javafx.geometry.Insets;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.paint.Color;
import trafiqueishon.ruta.LuisYPaletaColores;

/**
 * Marco del croquis: el mapa con un borde exterior de franja verde delgada y una
 * línea amarilla. Solo organiza la presentación; no calcula rutas ni guarda archivos.
 */
public class LuisYVistaCroquis extends BorderPane {

    private static final double GROSOR_FRANJA = 6;
    private static final double GROSOR_LINEA = 1.5;

    private final LuisYMapaRutasCanvas mapa;
    private final Border borde = crearBorde();

    public LuisYVistaCroquis(LuisYMapaRutasCanvas mapa) {
        this.mapa = mapa;
        setCenter(mapa);
        setModoCroquis(true);
    }

    /** Activa o desactiva el aspecto de croquis (mapa blanco con tema claro y borde). */
    public void setModoCroquis(boolean activo) {
        mapa.setModoCroquis(activo);
        setBorder(activo ? borde : null);
    }

    private static Border crearBorde() {
        BorderStroke franjaVerde = new BorderStroke(
                Color.web(LuisYPaletaColores.COLOR_RUTA_ACTIVA), BorderStrokeStyle.SOLID,
                CornerRadii.EMPTY, new BorderWidths(GROSOR_FRANJA));
        double centro = (GROSOR_FRANJA - GROSOR_LINEA) / 2;
        BorderStroke lineaAmarilla = new BorderStroke(
                Color.YELLOW, BorderStrokeStyle.SOLID, CornerRadii.EMPTY,
                new BorderWidths(GROSOR_LINEA), new Insets(centro));
        return new Border(franjaVerde, lineaAmarilla);
    }
}
