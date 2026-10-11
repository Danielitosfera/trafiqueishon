package trafiqueishon.simulacion;

import java.util.List;

public record RutaMicro(String nombre, String empresa, List<String> puntosIntermedios,
                        int frecuenciaMinutos, int unidades, String logoPath) {
    public RutaMicro {
        if (nombre == null || nombre.isBlank() || puntosIntermedios == null
                || puntosIntermedios.size() < 2 || frecuenciaMinutos < 1
                || frecuenciaMinutos > 240 || unidades < 1 || unidades > 500) {
            throw new IllegalArgumentException("Configuracion de ruta de micro invalida.");
        }
        empresa = empresa == null || empresa.isBlank() ? "Empresa local" : empresa.trim();
        puntosIntermedios = List.copyOf(puntosIntermedios);
        logoPath = logoPath == null ? "" : logoPath.trim();
    }

    public RutaMicro(String nombre, List<String> puntosIntermedios,
                     int frecuenciaMinutos, int unidades, String logoPath) {
        this(nombre, "Empresa local", puntosIntermedios,
                frecuenciaMinutos, unidades, logoPath);
    }

    public RutaMicro(String nombre, List<String> puntosIntermedios,
                     int frecuenciaMinutos, int unidades) {
        this(nombre, "Empresa local", puntosIntermedios, frecuenciaMinutos, unidades, "");
    }
}
