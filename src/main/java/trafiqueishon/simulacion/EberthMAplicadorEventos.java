package trafiqueishon.simulacion;

import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.geo.PieroGNodo;

import java.util.ArrayList;
import java.util.List;

/**
 * Aplica los eventos de tráfico sobre el grafo, cada tipo con su propio comportamiento:
 * <ul>
 *   <li>Lluvia (zonal): velocidad x0.7 solo para los vehículos dentro de 1 km del clic; la
 *       aplica el simulador por vehículo ({@link #estaBajoLluvia}), no cambia el grafo.</li>
 *   <li>Trancadera / vendedores: velocidad x0.1 / x0.5 en el tramo del clic, sin cerrarlo.</li>
 *   <li>Vía cerrada / accidente: tramo bloqueado (velocidad 0); los pathfinders desvían.</li>
 *   <li>Semáforo roto: el semáforo del nodo queda en rojo permanente.</li>
 *   <li>Policía de tránsito: agiliza el cruce; el semáforo del nodo reduce su rojo a la mitad.</li>
 *   <li>Manifestación: el nodo y todos sus tramos adyacentes quedan bloqueados.</li>
 * </ul>
 * Debe ejecutarse en el hilo del motor de simulación para no modificar el grafo
 * mientras los vehículos lo recorren.
 */
public final class EberthMAplicadorEventos {

    private static final double METROS_POR_GRADO = 111_320;
    /** Distancia máxima al clic para considerar un tramo o nodo como el afectado. */
    private static final double RADIO_BUSQUEDA_PUNTUAL_METROS = 80;
    /** Distancia al evento dentro de la cual una parada recibe más pasajeros por vendedores. */
    private static final double RADIO_DEMANDA_VENDEDORES_METROS = 150;
    /** Tope del factor de tiempo de viaje, para que varios eventos apilados no lo disparen. */
    private static final double FACTOR_TRAFICO_MAXIMO = 100;

    /** Tramos que dejó cerrados el conjunto de eventos y tramos con trancadera (para dibujarlos). */
    public record Resultado(List<PieroGEdge> cerradas, List<PieroGEdge> trancaderas) {}

    private EberthMAplicadorEventos() {}

    /**
     * Reinicia el estado de todos los tramos y aplica los eventos.
     *
     * @return tramos cerrados y tramos con trancadera
     */
    public static Resultado aplicar(PieroGGrafoVial grafo, List<EberthMEventoTrafico> eventos) {
        List<PieroGEdge> edges = grafo.getEdges();
        for (PieroGEdge edge : edges) {
            if (edge.cerrado) edge.cerrado = false;
            if (edge.factorTrafico != 1.0) edge.factorTrafico = 1.0;
        }
        for (PieroGNodo nodo : grafo.getNodos()) {
            if (nodo.semaforoRoto) nodo.semaforoRoto = false;
            if (nodo.policiaTransito) nodo.policiaTransito = false;
        }
        List<PieroGEdge> cerradas = new ArrayList<>();
        List<PieroGEdge> trancaderas = new ArrayList<>();
        for (EberthMEventoTrafico evento : eventos) {
            EberthMTipoEvento tipo = evento.tipo();
            if (tipo.esZonal()) {
                continue;
            } else if (tipo.afectaNodo()) {
                PieroGNodo nodo = nodoCercano(grafo, evento);
                if (nodo != null) nodo.semaforoRoto = true;
            } else if (tipo.esPolicia()) {
                PieroGNodo nodo = nodoCercano(grafo, evento);
                if (nodo == null) continue;
                nodo.policiaTransito = true;
            } else if (tipo.bloqueaNodo()) {
                PieroGNodo nodo = nodoCercano(grafo, evento);
                if (nodo == null) continue;
                nodo.salientes.forEach(e -> cerrar(e, cerradas));
                nodo.entrantes.forEach(e -> cerrar(e, cerradas));
            } else if (tipo.bloqueaVia() || tipo.ralentizaTramo()) {
                PieroGEdge mejor = tramoMasCercano(edges, evento);
                if (mejor == null) continue;
                aplicarATramoYContrario(mejor, tipo, cerradas);
                if (tipo == EberthMTipoEvento.TRANCADERA) {
                    trancaderas.add(mejor);
                    for (PieroGEdge e : mejor.destino.salientes) {
                        if (e.destino == mejor.origen) trancaderas.add(e);
                    }
                }
            }
        }
        return new Resultado(List.copyOf(cerradas), List.copyOf(trancaderas));
    }

    /**
     * Multiplicador de la tasa de generación de pasajeros de una parada: x3 (+200 %) si hay
     * vendedores ambulantes cerca. No se acumula con varios eventos.
     */
    public static double multiplicadorDemanda(PieroGNodo parada, List<EberthMEventoTrafico> eventos) {
        for (EberthMEventoTrafico evento : eventos) {
            if (evento.tipo() != EberthMTipoEvento.VENDEDORES_AMBULANTES) continue;
            if (distanciaMetros(parada.lon, parada.lat, evento.lon(), evento.lat())
                    <= RADIO_DEMANDA_VENDEDORES_METROS) {
                return EberthMTipoEvento.MULTIPLICADOR_DEMANDA_VENDEDORES;
            }
        }
        return 1.0;
    }

    /** {@code true} si el punto cae dentro del radio de alguna lluvia activa. */
    public static boolean estaBajoLluvia(double lon, double lat, List<EberthMEventoTrafico> eventos) {
        for (EberthMEventoTrafico evento : eventos) {
            if (evento.tipo() == EberthMTipoEvento.LLUVIA
                    && distanciaMetros(lon, lat, evento.lon(), evento.lat())
                    <= evento.radioEfectivoMetros()) {
                return true;
            }
        }
        return false;
    }

    private static PieroGNodo nodoCercano(PieroGGrafoVial grafo, EberthMEventoTrafico evento) {
        return grafo.nodoMasCercanoLonLat(evento.lon(), evento.lat());
    }

    private static void aplicarATramoYContrario(PieroGEdge edge, EberthMTipoEvento tipo,
                                                List<PieroGEdge> cerradas) {
        aplicarATramo(edge, tipo, cerradas);
        for (PieroGEdge e : edge.destino.salientes) {
            if (e.destino == edge.origen) aplicarATramo(e, tipo, cerradas);
        }
    }

    private static void aplicarATramo(PieroGEdge edge, EberthMTipoEvento tipo, List<PieroGEdge> cerradas) {
        if (tipo.bloqueaVia()) cerrar(edge, cerradas);
        else ralentizar(edge, tipo.multiplicadorVelocidad());
    }

    /** Velocidad x{@code multiplicador} equivale a tiempo de viaje x1/{@code multiplicador}. */
    private static void ralentizar(PieroGEdge edge, double multiplicador) {
        edge.factorTrafico = Math.min(FACTOR_TRAFICO_MAXIMO, edge.factorTrafico / multiplicador);
    }

    private static PieroGEdge tramoMasCercano(List<PieroGEdge> edges, EberthMEventoTrafico evento) {
        PieroGEdge mejor = null;
        double mejorDistancia = RADIO_BUSQUEDA_PUNTUAL_METROS;
        for (PieroGEdge edge : edges) {
            double d = distanciaPuntoTramo(evento.lon(), evento.lat(), edge);
            if (d < mejorDistancia) {
                mejorDistancia = d;
                mejor = edge;
            }
        }
        return mejor;
    }

    private static double distanciaMetros(double lon1, double lat1, double lon2, double lat2) {
        double dx = (lon2 - lon1) * METROS_POR_GRADO * Math.cos(Math.toRadians(lat1));
        double dy = (lat2 - lat1) * METROS_POR_GRADO;
        return Math.hypot(dx, dy);
    }

    private static void cerrar(PieroGEdge edge, List<PieroGEdge> cerradas) {
        if (edge.cerrado) return;
        edge.cerrado = true;
        cerradas.add(edge);
    }

    /** Distancia aproximada en metros entre un punto y un tramo (proyección local). */
    public static double distanciaPuntoTramo(double lon, double lat, PieroGEdge edge) {
        double escalaX = METROS_POR_GRADO * Math.cos(Math.toRadians(lat));
        double ax = edge.origen.lon * escalaX;
        double ay = edge.origen.lat * METROS_POR_GRADO;
        double bx = edge.destino.lon * escalaX;
        double by = edge.destino.lat * METROS_POR_GRADO;
        double px = lon * escalaX;
        double py = lat * METROS_POR_GRADO;
        double dx = bx - ax, dy = by - ay;
        double l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.clamp(((px - ax) * dx + (py - ay) * dy) / l2, 0, 1);
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }
}
