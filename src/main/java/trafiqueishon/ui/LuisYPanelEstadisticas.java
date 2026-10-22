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
            "Unidades con incidencia", "Tiempo simulado", "Pasajeros que bajaron",
            "Pasajeros caminando", "Satisfacción", "Tasa de desistimiento",
            "Zonas sin cobertura", "Pasajeros sin servicio", "Pasajeros en transbordo",
            "Pasajeros Totales Generados", "Cobertura de la demanda"};
    private static final int INDICE_SATISFACCION = 12;
    /** Por debajo de este porcentaje de satisfacción se muestra la alerta roja. */
    private static final double SATISFACCION_MINIMA = 50;
    private static final String ESTILO_ALERTA = "-fx-text-fill: #e53935; -fx-font-weight: bold;";
    private static final int INDICE_ZONAS_SIN_COBERTURA = 14;
    /** Se muestra justo debajo de «Rutas activas», pero va al final del arreglo. */
    private static final int INDICE_GENERADOS = 17;
    /** Cobertura de la demanda de la ciudad por las rutas; no depende de que corra la simulación. */
    private static final int INDICE_COBERTURA = 18;
    private String textoCobertura = "—";
    /** Cuántas zonas sin cobertura disparan la alerta de demanda insatisfecha. */
    private static final int ZONAS_SIN_COBERTURA_ALERTA = 3;
    private final Label alertaCobertura = new Label();
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
        int fila = 0;
        for (int i = 0; i < ETIQUETAS.length; i++) {
            if (i == INDICE_GENERADOS) continue;
            if (i == 2) fila = agregarFila(tabla, fila, INDICE_GENERADOS);
            if (i == INDICE_ZONAS_SIN_COBERTURA) {
                Label seccion = new Label("🗺 Cobertura del Mapa");
                seccion.getStyleClass().add("subtitulo");
                tabla.add(seccion, 0, fila++, 2, 1);
            }
            fila = agregarFila(tabla, fila, i);
        }
        alertaCobertura.setWrapText(true);
        alertaCobertura.setMaxWidth(280);
        alertaCobertura.setStyle(ESTILO_ALERTA);
        alertaCobertura.setVisible(false);
        alertaCobertura.setManaged(false);
        getChildren().addAll(cabecera, new Separator(), tabla, alertaCobertura);
        setPadding(new Insets(10));
        setMinWidth(Region.USE_PREF_SIZE);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
    }

    private int agregarFila(GridPane tabla, int fila, int indice) {
        Label nombre = new Label(ETIQUETAS[indice]);
        nombre.getStyleClass().add("texto-secundario");
        valores[indice] = new Label("—");
        valores[indice].getStyleClass().add("valor-estadistica");
        tabla.addRow(fila, nombre, valores[indice]);
        return fila + 1;
    }

    /** Cobertura (0-1) de la demanda de la ciudad por las rutas actuales; {@code null} = sin dato. */
    public void setCoberturaDemanda(Double cobertura) {
        textoCobertura = cobertura == null ? "—" : String.format("%.0f %%", cobertura * 100);
        valores[INDICE_COBERTURA].setText(textoCobertura);
        valores[INDICE_COBERTURA].setStyle(cobertura != null && cobertura < 0.5 ? ESTILO_ALERTA : "");
    }

    /** Refresca los valores desde la foto del motor; sin simulación todo queda en «—». */
    public void actualizar(Instantanea foto) {
        valores[INDICE_COBERTURA].setText(textoCobertura);
        if (!foto.haySimulacion()) {
            for (int i = 0; i < valores.length; i++) if (i != INDICE_COBERTURA) valores[i].setText("—");
            alertaCobertura.setVisible(false);
            alertaCobertura.setManaged(false);
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
        valores[10].setText(formato(foto.pasajerosBajaron()));
        valores[11].setText(formato(foto.pasajerosCaminando()));
        int transportados = foto.pasajerosTransportados();
        int rendidos = foto.pasajerosAbandonaron();
        int atendidos = transportados + rendidos;
        Label satisfaccion = valores[INDICE_SATISFACCION];
        if (atendidos == 0) {
            satisfaccion.setText("—");
            satisfaccion.setStyle("");
        } else {
            double pct = 100.0 * transportados / atendidos;
            boolean baja = pct < SATISFACCION_MINIMA;
            satisfaccion.setText(String.format("%s%.1f %%", baja ? "⚠ " : "", pct));
            satisfaccion.setStyle(baja ? ESTILO_ALERTA : "");
        }
        // Generados = a bordo + esperando + caminando + rendidos + llegados (lo cuenta el motor).
        long generados = foto.pasajerosGenerados();
        valores[INDICE_GENERADOS].setText(formato(generados));
        valores[13].setText(generados == 0 ? "—" : String.format("%.1f %%", 100.0 * rendidos / generados));
        valores[INDICE_ZONAS_SIN_COBERTURA].setText(formato(foto.zonasSinCobertura()));
        valores[INDICE_ZONAS_SIN_COBERTURA + 1].setText(formato(foto.pasajerosSinCobertura()));
        valores[INDICE_ZONAS_SIN_COBERTURA + 2].setText(formato(foto.transbordos()));
        boolean alerta = foto.zonasSinCobertura() > ZONAS_SIN_COBERTURA_ALERTA;
        alertaCobertura.setVisible(alerta);
        alertaCobertura.setManaged(alerta);
        if (alerta) {
            alertaCobertura.setText("⚠️ Demanda insatisfecha detectada en " + foto.peorZonaSinCobertura()
                    + ". Sugerencia: Crear nueva ruta");
        }
    }

    private static String formato(long n) {
        return String.format("%,d", n);
    }
}
