package trafiqueishon.simulacion;

/**
 * Factor de demanda de pasajeros de una parada según la categoría de los POIs cercanos, la hora,
 * el tipo de día y la demografía de la ciudad. Las tablas son estimaciones razonadas, no datos
 * medidos: conviene calibrarlas con aforos reales.
 */
public final class EberthMPerfilDemanda {
    private EberthMPerfilDemanda() {}

    // Orden: EDUCACION, COMERCIO, SALUD, TURISMO, TRABAJO, OTRO.
    private static final double[] MANANA = {2.5, 1.0, 1.2, 0.6, 2.5, 1.5};    // 06:00–09:00
    private static final double[] MEDIODIA = {1.3, 2.2, 2.0, 1.2, 1.2, 1.5};  // 11:00–14:00
    private static final double[] TARDE = {1.5, 2.2, 1.0, 1.0, 2.5, 2.0};     // 17:00–20:00
    private static final double FACTOR_NOCHE = 0.1;                            // 22:00–05:00

    /** Multiplicador relativo por tipo de día (LABORAL, SABADO, DOMINGO, FERIADO). */
    private static final double[][] POR_DIA = {
            {1, 0.20, 0.03, 0.03},  // EDUCACION: casi sin clases
            {1, 1.50, 1.40, 1.30},  // COMERCIO: ferias y compras
            {1, 0.70, 0.50, 0.50},  // SALUD: solo consulta reducida y emergencias
            {1, 1.80, 2.20, 2.20},  // TURISMO: ocio
            {1, 0.45, 0.10, 0.10},  // TRABAJO: oficinas cerradas
            {1, 1.00, 1.00, 1.00}}; // OTRO

    /** Perfil horario base de una categoría en un día laborable. */
    public static double perfilHorario(EberthMCategoriaPoi categoria, int minutoDelDia) {
        int hora = Math.floorMod(minutoDelDia, 24 * 60) / 60;
        int c = categoria.ordinal();
        if (hora >= 22 || hora < 5) return FACTOR_NOCHE;
        if (hora == 5) return 0.5;
        if (hora < 9) return MANANA[c];
        if (hora >= 11 && hora < 14) return MEDIODIA[c];
        if (hora >= 17 && hora < 20) return TARDE[c];
        if (hora >= 20) return 0.5;
        return 1.0;
    }

    /** Estudiantes → educación; trabajadores → trabajo; jubilados → salud y comercio. */
    public static double factorDemografico(EberthMCategoriaPoi categoria,
                                           EberthMParametrosCiudad ciudad) {
        if (ciudad == null) return 1.0;
        return switch (categoria) {
            case EDUCACION -> ciudad.getPorcentajeEstudiantes() / 25.0;
            case TRABAJO -> ciudad.getPorcentajeTrabajadores() / 50.0;
            case SALUD, COMERCIO -> (1 + ciudad.getPorcentajeJubilados() / 25.0) / 2;
            default -> 1.0;
        };
    }

    public static double factor(EberthMCategoriaPoi categoria, int minutoDelDia,
                                EberthMTipoDia dia, EberthMParametrosCiudad ciudad) {
        return dia.aplanar(perfilHorario(categoria, minutoDelDia))
                * POR_DIA[categoria.ordinal()][dia.ordinal()]
                * dia.demandaGlobal()
                * factorDemografico(categoria, ciudad);
    }

    /** Factor de una parada sin POIs cercanos: el perfil general de siempre, ajustado al día. */
    public static double factorGeneral(int minutoDelDia, EberthMTipoDia dia) {
        return dia.aplanar(EberthMPerfilHoraPunta.multiplicadorDemanda(null, minutoDelDia, dia, false))
                * dia.demandaGlobal();
    }
}
