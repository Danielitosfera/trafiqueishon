package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EberthMDemandaPasajerosTest {

    private static final int MEDIODIA_NORMAL = 10 * 60;
    private static final int HORA_PUNTA_TARDE = 18 * 60;
    private static final int MADRUGADA = 2 * 60;

    @Test
    void repartePorUnidadYLimitaElAbordajeADieciocho() {
        EberthMDemandaPasajeros demanda = new EberthMDemandaPasajeros(100, 4);

        assertEquals(25, demanda.demandaPorSalida());
        assertEquals(18, demanda.abordarEnSalida(MEDIODIA_NORMAL));
        assertEquals(7, demanda.pendientes());
    }

    @Test
    void acumulaLoQueNoAborda() {
        EberthMDemandaPasajeros demanda = new EberthMDemandaPasajeros(100, 4);

        demanda.abordarEnSalida(MEDIODIA_NORMAL);
        demanda.abordarEnSalida(MEDIODIA_NORMAL);

        assertEquals(14, demanda.pendientes());
    }

    @Test
    void aplicaElFactorHorarioDeDemanda() {
        int punta = new EberthMDemandaPasajeros(10, 1).abordarEnSalida(HORA_PUNTA_TARDE);
        int madrugada = new EberthMDemandaPasajeros(10, 1).abordarEnSalida(MADRUGADA);

        assertEquals(18, punta);
        assertEquals(2, madrugada);
    }

    @Test
    void sinDemandaNoAbordaNadie() {
        assertEquals(0, new EberthMDemandaPasajeros(0, 3).abordarEnSalida(HORA_PUNTA_TARDE));
    }

    @Test
    void rechazaParametrosInvalidos() {
        assertThrows(IllegalArgumentException.class, () -> new EberthMDemandaPasajeros(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new EberthMDemandaPasajeros(10, 0));
    }

    @Test
    void avisaCuandoLosPendientesDesbordan() {
        EberthMDemandaPasajeros demanda = new EberthMDemandaPasajeros(Integer.MAX_VALUE, 1);

        demanda.abordarEnSalida(MEDIODIA_NORMAL);

        assertThrows(ArithmeticException.class, () -> demanda.abordarEnSalida(MEDIODIA_NORMAL));
    }
}
