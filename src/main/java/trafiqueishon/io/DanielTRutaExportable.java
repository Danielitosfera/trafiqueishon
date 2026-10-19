package trafiqueishon.io;

import trafiqueishon.ruta.LuisYTrayecto;

import java.util.Objects;

public record DanielTRutaExportable(String nombre, String color, LuisYTrayecto trayecto) {
    public DanielTRutaExportable {
        if (nombre == null || nombre.isBlank() || color == null || color.isBlank()) {
            throw new IllegalArgumentException("La ruta exportable debe tener nombre y color.");
        }
        Objects.requireNonNull(trayecto, "El trayecto exportable es obligatorio.");
    }
}
