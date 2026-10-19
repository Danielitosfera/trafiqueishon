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

    public static double factorDemanda(int minutosDelDia) {
        int hora = Math.floorMod(minutosDelDia, 24 * 60) / 60;
        if (hora >= 6 && hora < 9) return 2.5;
        if (hora >= 12 && hora < 14) return 1.8;
        if (hora >= 17 && hora < 20) return 2.8;
        if (hora >= 22 || hora < 5) return 0.2;
        return 1.0;
    }

    public static void aplicar(PieroGGrafoVial grafo, int minutosDelDia) {
        aplicar(grafo, minutosDelDia, EberthMTipoDia.LABORAL);
    }

    public static void aplicar(PieroGGrafoVial grafo, int minutosDelDia, EberthMTipoDia dia) {
        double factor = factor(minutosDelDia, dia);
        for (PieroGEdge edge : grafo.getEdges()) edge.factorHoraPunta = factor;
    }
}
