package trafiqueishon.ruta;

/**
 * Única fuente de los colores de recorrido. El mapa, la leyenda, el panel de
 * paradas, los exportadores y el simulador leen estos valores; ningún otro
 * archivo debe repetir los códigos hexadecimales.
 *
 * <p>Se expresan como texto {@code #RRGGBB} para que los módulos sin JavaFX
 * (rutas, simulación, datos) puedan usarlos sin depender de la interfaz.</p>
 */
public final class LuisYPaletaColores {

    /** Recorrido de partida (ida): azul. Legible sobre fondo blanco y oscuro. */
    public static final String COLOR_RUTA_IDA = "#1E6FFF";

    /** Recorrido de retorno (vuelta): rojo. */
    public static final String COLOR_RUTA_VUELTA = "#E5243B";

    /** Unidad en movimiento sobre la ruta: verde. */
    public static final String COLOR_RUTA_ACTIVA = "#2E9E44";

    /** Color de marcador para cada categoría de punto de interés. */
    public static String colorCategoria(LuisYPuntoImportante.TipoPunto tipo) {
        if (tipo == null) return "#F9E2AF";
        return switch (tipo) {
            case TERMINAL, TRANSPORTE -> "#00B8D9";
            // Mismas categorías que usa la demanda (EberthMCategoriaPoi).
            case EDUCACION -> "#2979FF";                       // azul
            case MERCADO, COMERCIO -> "#FFD600";               // amarillo
            case SALUD -> "#FF1744";                           // rojo
            case TURISMO, PARQUE, PLAZA, DEPORTE -> "#00C853"; // verde (ocio)
            case INSTITUCION -> "#FF9100";                     // naranja (trabajo)
            case ORIGEN -> "#39FF14";
            case DESTINO -> "#FF3B5C";
            default -> "#F9E2AF";
        };
    }

    private LuisYPaletaColores() {}
}
