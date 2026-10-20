package trafiqueishon.simulacion;

import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGGrafoVial;

public final class EberthMPerfilHoraPunta {
    private EberthMPerfilHoraPunta() {}

    public static double factor(int minutosDelDia) {
        int minuto = Math.floorMod(minutosDelDia, 24 * 60);
        int hora = minuto / 60;
        if (hora >= 7 && hora < 9) return 2.2;
        if (hora >= 12 && hora < 14) return 1.5;
        if (hora >= 17 && hora < 20) return 2.0;
        return 1.0;
    }

    /** Congestión por hora: sábados, domingos y feriados tienen picos mucho más suaves. */
    public static double factor(int minutosDelDia, EberthMTipoDia dia) {
        return dia.aplanar(factor(minutosDelDia));
    }

    public static final double MULTIPLICADOR_HORA_PUNTA = 2.5;
    public static final double MULTIPLICADOR_MADRUGADA = 0.1;

    /**
     * Punto único del multiplicador de demanda (paradas y despachador): día laboral, sin hora
     * punta forzada. Ver {@link #multiplicadorDemanda(EberthMParametrosCiudad, int, EberthMTipoDia, boolean)}.
     */
    public static double multiplicadorDemanda(EberthMParametrosCiudad ciudad, int hora, int minuto) {
        return multiplicadorDemanda(ciudad, hora * 60 + minuto, EberthMTipoDia.LABORAL, false);
    }

    /**
     * Multiplicador de la generación de pasajeros según el reloj: dentro de las horas punta
     * configuradas (o forzadas) ×2,5; de madrugada (22:00–05:00) ×0,1; el resto del día ×1.
     */
    public static double multiplicadorDemanda(EberthMParametrosCiudad ciudad, int minutosDelDia,
                                              EberthMTipoDia dia, boolean forzada) {
        if (forzada || (ciudad != null && ciudad.esHoraPunta(minutosDelDia, dia))) {
            return MULTIPLICADOR_HORA_PUNTA;
        }
        int hora = Math.floorMod(minutosDelDia, 24 * 60) / 60;
        return hora < 5 || hora >= 22 ? MULTIPLICADOR_MADRUGADA : 1.0;
    }

    public static void aplicar(PieroGGrafoVial grafo, int minutosDelDia) {
        aplicar(grafo, minutosDelDia, EberthMTipoDia.LABORAL);
    }

    public static void aplicar(PieroGGrafoVial grafo, int minutosDelDia, EberthMTipoDia dia) {
        double factor = factor(minutosDelDia, dia);
        for (PieroGEdge edge : grafo.getEdges()) edge.factorHoraPunta = factor;
    }
}
