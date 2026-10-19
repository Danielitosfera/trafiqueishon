package trafiqueishon.geo;

import trafiqueishon.simulacion.EberthMSemaforo;

import java.util.ArrayList;
import java.util.List;

public class PieroGNodo {
    public final int id;
    public final double x;
    public final double y;
    public final double lon;
    public final double lat;
    // Capacidad inicial pequeña: una intersección típica tiene 2-4 tramos.
    public final List<PieroGEdge> salientes = new ArrayList<>(3);
    public final List<PieroGEdge> entrantes = new ArrayList<>(3);
    /** Semáforo averiado: los vehículos deben detenerse al cruzar este nodo. */
    public volatile boolean semaforoRoto;
    /** Semáforo de la intersección; {@code null} si el nodo no cumple los criterios de {@link PieroGGrafoVial#regenerarSemaforos}. */
    public volatile EberthMSemaforo semaforo;

    public PieroGNodo(int id, double x, double y, double lon, double lat) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.lon = lon;
        this.lat = lat;
    }

    /** Ajusta las listas de adyacencia a su tamaño real (ahorra memoria en mapas masivos). */
    void compactar() {
        ((ArrayList<PieroGEdge>) salientes).trimToSize();
        ((ArrayList<PieroGEdge>) entrantes).trimToSize();
    }

    @Override
    public String toString() {
        return "Nodo#" + id + "(" + String.format("%.5f,%.5f", lon, lat) + ")";
    }
}