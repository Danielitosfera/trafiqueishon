package trafiqueishon.simulacion;

/**
 * Tipo de día simulado. Los factores son estimaciones: en sistemas de transporte público el
 * sábado mueve cerca del 70 % de los viajes de un día laborable y el domingo/feriado entre el
 * 53 % y el 61 %, con picos horarios mucho menos marcados.
 */
public enum EberthMTipoDia {
    LABORAL("Laboral (lun–vie)", 1.00, 1.0, 1.0),
    SABADO("Sábado", 0.70, 0.5, 1.2),
    DOMINGO("Domingo", 0.55, 0.3, 0.9),
    FERIADO("Feriado", 0.55, 0.3, 1.3);

    private final String etiqueta;
    private final double demandaGlobal;
    private final double aplanamientoPicos;
    private final double factorAccidentes;

    EberthMTipoDia(String etiqueta, double demandaGlobal, double aplanamientoPicos,
                   double factorAccidentes) {
        this.etiqueta = etiqueta;
        this.demandaGlobal = demandaGlobal;
        this.aplanamientoPicos = aplanamientoPicos;
        this.factorAccidentes = factorAccidentes;
    }

    /** Fracción de la demanda total de un día laborable. */
    public double demandaGlobal() {
        return demandaGlobal;
    }

    /** 1 = picos completos; valores menores acercan los picos horarios al valor normal (1.0). */
    public double aplanamientoPicos() {
        return aplanamientoPicos;
    }

    /** Multiplicador de la probabilidad de accidente respecto a un día laborable. */
    public double factorAccidentes() {
        return factorAccidentes;
    }

    public boolean esLaboral() {
        return this == LABORAL;
    }

    /** Acerca un factor horario mayor que 1 a 1.0 según el aplanamiento; los menores no cambian. */
    public double aplanar(double factorHorario) {
        return factorHorario > 1 ? 1 + (factorHorario - 1) * aplanamientoPicos : factorHorario;
    }

    @Override
    public String toString() {
        return etiqueta;
    }
}
