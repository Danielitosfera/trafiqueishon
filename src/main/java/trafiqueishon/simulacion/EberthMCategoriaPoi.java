package trafiqueishon.simulacion;

import trafiqueishon.ruta.LuisYPuntoImportante.TipoPunto;

/** Categoría lógica de un POI para decidir cuánta gente aparece cerca de él a cada hora. */
public enum EberthMCategoriaPoi {
    EDUCACION, COMERCIO, SALUD, TURISMO, TRABAJO, OTRO;

    /** Popularidad (0-100) típica de la categoría. */
    public int popularidad() {
        return switch (this) {
            case EDUCACION -> 90;
            case COMERCIO -> 85;
            case SALUD -> 80;
            case TURISMO -> 50;
            case TRABAJO -> 60;
            case OTRO -> 30;
        };
    }

    /** Personas por hora (promedio estimado) que mueve un POI de la categoría. */
    public int personasPromedio() {
        return switch (this) {
            case EDUCACION -> 500;
            case COMERCIO -> 400;
            case SALUD -> 300;
            case TURISMO -> 100;
            case TRABAJO -> 200;
            case OTRO -> 50;
        };
    }

    /** Colegios y universidades; mercados y tiendas; hospitales; ocio; oficinas e instituciones. */
    public static EberthMCategoriaPoi desde(TipoPunto tipo) {
        if (tipo == null) return OTRO;
        return switch (tipo) {
            case EDUCACION -> EDUCACION;
            case MERCADO, COMERCIO -> COMERCIO;
            case SALUD -> SALUD;
            case TURISMO, PARQUE, PLAZA, DEPORTE -> TURISMO;
            case INSTITUCION -> TRABAJO;
            default -> OTRO;
        };
    }
}
