package trafiqueishon.ui;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import trafiqueishon.simulacion.EberthMMotorSimulacion.Instantanea;
import trafiqueishon.simulacion.EberthMParadaEspera;
import trafiqueishon.simulacion.EberthMPosicionVehiculo;

import java.util.HashSet;
import java.util.Set;

/**
 * Cuadro con las estadísticas de la simulación en tiempo real. Se instancia dos veces: dentro de
 * la sección «Simulación» del panel lateral y flotante sobre el mapa (con botón para ocultarlo)
 * cuando el panel lateral está cerrado.
 */
public final class LuisYPanelEstadisticas extends VBox {

    private static final String[] ETIQUETAS = {
            "Micros circulando", "Rutas activas", "Pasajeros a bordo", "Pasajeros que subieron",
            "Pasajeros esperando", "Espera promedio", "Se cansaron de esperar", "Ocupación media",
            "Unidades con incidencia", "Tiempo simulado"};
    private final Label[] valores = new Label[ETIQUETAS.length];

    /** @param alOcultar acción del botón ✕; {@code null} omite el botón (versión del panel lateral) */
    public LuisYPanelEstadisticas(Runnable alOcultar) {
        getStyleClass().add("panel-estadisticas");
        setSpacing(6);
        Label titulo = new Label("📊 Estadísticas en vivo");
        titulo.getStyleClass().add("subtitulo");
        HBox cabecera = new HBox(8, titulo);
        cabecera.setAlignment(Pos.CENTER_LEFT);
        if (alOcultar != null) {
            Region relleno = new Region();
            HBox.setHgrow(relleno, Priority.ALWAYS);
            Button ocultar = new Button("✕");
            ocultar.getStyleClass().add("boton-compacto");
            ocultar.setOnAction(e -> alOcultar.run());
            cabecera.getChildren().addAll(relleno, ocultar);
        }

        GridPane tabla = new GridPane();
        tabla.setHgap(14);
        tabla.setVgap(4);
        ColumnConstraints izquierda = new ColumnConstraints();
        izquierda.setHgrow(Priority.ALWAYS);
        ColumnConstraints derecha = new ColumnConstraints();
        derecha.setHalignment(HPos.RIGHT);
        tabla.getColumnConstraints().addAll(izquierda, derecha);
        for (int i = 0; i < ETIQUETAS.length; i++) {
            Label nombre = new Label(ETIQUETAS[i]);
            nombre.getStyleClass().add("texto-secundario");
            valores[i] = new Label("—");
            valores[i].getStyleClass().add("valor-estadistica");
            tabla.addRow(i, nombre, valores[i]);
        }
        getChildren().addAll(cabecera, new Separator(), tabla);
        setPadding(new Insets(10));
        setMinWidth(Region.USE_PREF_SIZE);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
    }

    /** Refresca los valores desde la foto del motor; sin simulación todo queda en «—». */
    public void actualizar(Instantanea foto) {
        if (!foto.haySimulacion()) {
            for (Label v : valores) v.setText("—");
            return;
        }
        Set<String> rutas = new HashSet<>();
        int abordo = 0;
        for (EberthMPosicionVehiculo v : foto.vehiculos()) {
            rutas.add(v.idRuta());
            abordo += v.pasajeros();
        }
        int esperando = 0;
        for (EberthMParadaEspera p : foto.paradas()) esperando += p.esperando();
        valores[0].setText(formato(foto.vehiculos().size()));
        valores[1].setText(formato(rutas.size()));
        valores[2].setText(formato(abordo));
        valores[3].setText(formato(foto.pasajerosTransportados()));
        valores[4].setText(formato(esperando));
        valores[5].setText(String.format("%.1f min", foto.esperaPromedioMin()));
        valores[6].setText(formato(foto.pasajerosAbandonaron()));
        valores[7].setText(String.format("%.0f %%", foto.ocupacionPromedio() * 100));
        valores[8].setText(formato(foto.vehiculosConIncidencia()));
        valores[9].setText(String.format("%.1f min", foto.segundosSimulados() / 60));
    }

    private static String formato(int n) {
        return String.format("%,d", n);
    }
}
