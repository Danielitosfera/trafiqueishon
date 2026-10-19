package trafiqueishon.ruta;

import trafiqueishon.geo.PieroGEdge;

import java.util.List;

public record LuisYTrayecto(
        String id,
        String nombre,
        String empresa,
        List<LuisYPuntoImportante> puntos,
        List<PieroGEdge> edges,
        double distanciaMetros,
        double tiempoSegundos,
        String sentido) {

    public LuisYTrayecto {
        puntos = List.copyOf(puntos);
        edges = List.copyOf(edges);
        sentido = sentido == null ? "PARTIDA" : sentido;
    }

    public String resumen() {
        double km = distanciaMetros / 1000.0;
        int min = (int) (tiempoSegundos / 60);
        int seg = (int) Math.round(tiempoSegundos % 60);
        return String.format("%.2f km  |  %d min %02d s  |  %d tramos",
                km, min, seg, edges.size());
    }

    public boolean esVacia() { return edges.isEmpty(); }

    public boolean esVacio() { return esVacia(); }
}