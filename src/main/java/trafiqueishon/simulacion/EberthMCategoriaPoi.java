package trafiqueishon.simulacion;

import trafiqueishon.ruta.LuisYPuntoImportante.TipoPunto;

/** Categoría lógica de un POI para decidir cuánta gente aparece cerca de él a cada hora. */
public enum EberthMCategoriaPoi {
    EDUCACION, COMERCIO, SALUD, TURISMO, TRABAJO, OTRO;

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
