package trafiqueishon.simulacion;

import trafiqueishon.ruta.LuisYTrayecto;

import java.util.Objects;

public record EberthMServicioMicro(
        String id,
        String nombre,
        LuisYTrayecto trayecto,
        LuisYTrayecto trayectoVuelta,
        int frecuenciaMinutos,
        int unidadesDisponibles,
        int pasajerosEsperando,
        String color,
        EberthMTipoRetorno retorno) {

    public static final int MAX_UNIDADES = 6_000;
    /** Demanda base máxima por ruta (pasajeros por hora que llegan a las paradas). */
    public static final int MAX_PASAJEROS = 50_000;

    /** Servicio con retorno inverso (el caso habitual). */
    public EberthMServicioMicro(String id, String nombre, LuisYTrayecto trayecto,
                                LuisYTrayecto trayectoVuelta, int frecuenciaMinutos,
                                int unidadesDisponibles, int pasajerosEsperando, String color) {
        this(id, nombre, trayecto, trayectoVuelta, frecuenciaMinutos, unidadesDisponibles,
                pasajerosEsperando, color, EberthMTipoRetorno.INVERSO);
    }

    public EberthMServicioMicro {
        if (retorno == null) retorno = EberthMTipoRetorno.INVERSO;
        if (id == null || id.isBlank() || nombre == null || nombre.isBlank()
                || frecuenciaMinutos < 1 || frecuenciaMinutos > 180
                || unidadesDisponibles < 1 || unidadesDisponibles > MAX_UNIDADES
                || pasajerosEsperando < 0 || pasajerosEsperando > MAX_PASAJEROS
                || color == null || color.isBlank()) {
            throw new IllegalArgumentException("Configuración de ruta de micro inválida.");
        }
        Objects.requireNonNull(trayecto, "El trayecto de la micro es obligatorio.");
        if (trayecto.edges().isEmpty()) {
            throw new IllegalArgumentException("La ruta de micro debe tener tramos.");
        }
        Objects.requireNonNull(trayectoVuelta, "La ruta de retorno es obligatoria.");
        if (trayectoVuelta.edges().isEmpty()) {
            throw new IllegalArgumentException("La ruta de retorno debe tener tramos.");
        }
    }

    /**
     * {@code false} si la micro solo hace la ida. En ese caso {@link #trayectoVuelta()} es el
     * camino físico de regreso de la unidad vacía y no debe mostrarse ni exportarse.
     */
    public boolean tieneRetorno() {
        return retorno != EberthMTipoRetorno.SIN_RETORNO;
    }
}
