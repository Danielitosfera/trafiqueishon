package trafiqueishon.geo;

import java.util.ArrayList;
import java.util.List;

public class Nodo {
    public final int id;
    public final double x;     // 0..1
    public final double y;     // 0..1
    public final double lon;
    public final double lat;
    public final List<Edge> salientes = new ArrayList<>();
    public final List<Edge> entrantes = new ArrayList<>();

    public Nodo(int id, double x, double y, double lon, double lat) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.lon = lon;
        this.lat = lat;
    }

    @Override public String toString() {
        return "Nodo#" + id + "(" + String.format("%.5f,%.5f", lon, lat) + ")";
    }
}