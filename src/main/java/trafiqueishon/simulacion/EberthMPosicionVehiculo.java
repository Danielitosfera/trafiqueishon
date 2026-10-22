package trafiqueishon.simulacion;

import trafiqueishon.ruta.LuisYPaletaColores;

public record EberthMPosicionVehiculo(double x, double y, TipoVehiculo tipo,
                               int pasajeros, int capacidad, String idRuta, String nombreRuta,
                               String color, Estado estado,
                               double dirX, double dirY) {
    /** Micro: 18 sentados + 15 de pie. */
    public static final int ASIENTOS_MICRO = 18;
    public static final int CAPACIDAD_MICRO = 33;

    /** Sin rumbo conocido (dirX = dirY = 0): el mapa lo dibuja sobre la línea central. */
    public EberthMPosicionVehiculo(double x, double y, TipoVehiculo tipo,
                            int pasajeros, int capacidad, String idRuta, String nombreRuta,
                            String color, Estado estado) {
        this(x, y, tipo, pasajeros, capacidad, idRuta, nombreRuta, color, estado, 0, 0);
    }

    public EberthMPosicionVehiculo(double x, double y, TipoVehiculo tipo, int pasajeros,
                            int capacidad, String idRuta, String nombreRuta, String color) {
        this(x, y, tipo, pasajeros, capacidad, idRuta, nombreRuta, color, Estado.EN_RUTA);
    }

    public EberthMPosicionVehiculo(double x, double y, TipoVehiculo tipo) {
        this(x, y, tipo, 0, tipo == TipoVehiculo.BUS ? 50 : CAPACIDAD_MICRO, "", "", LuisYPaletaColores.COLOR_RUTA_ACTIVA,
                Estado.EN_RUTA);
    }

    public EberthMPosicionVehiculo(double x, double y, TipoVehiculo tipo, int pasajeros, int capacidad) {
        this(x, y, tipo, pasajeros, capacidad, "", "", LuisYPaletaColores.COLOR_RUTA_ACTIVA, Estado.EN_RUTA);
    }

    public enum Estado { EN_RUTA, EN_PARADA, DETENIDO, AVERIADO, SIN_RUTA }

    public enum TipoVehiculo {
        BUS, MINIBUS
    }
}
