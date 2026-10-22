package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static trafiqueishon.simulacion.EberthMSemaforo.Eje.HORIZONTAL;
import static trafiqueishon.simulacion.EberthMSemaforo.Eje.VERTICAL;

class EberthMSemaforoTest {

    @Test
    void websterRepartePorDemandaConVerdeMinimo() {
        EberthMSemaforo s = new EberthMSemaforo(1);
        s.setDemanda(10, 2);
        // y = 0,45 (tope por eje) y 0,125 → Y = 0,575; C = 17 / 0,425 = 40 s.
        assertEquals(40, s.cicloSegundos(), 1e-9);
        assertTrue(s.verdeSegundos(HORIZONTAL) > 1.5 * s.verdeSegundos(VERTICAL));
        assertEquals(40, s.verdeSegundos(HORIZONTAL) + s.verdeSegundos(VERTICAL), 1e-9);
        // El eje poco cargado nunca queda por debajo del verde mínimo (+ tiempo perdido de su fase).
        s.setDemanda(14, 1);
        assertTrue(s.verdeSegundos(VERTICAL) >= EberthMSemaforo.VERDE_MINIMO_SEGUNDOS);
        s.setDemanda(3, 3);
        assertEquals(s.verdeSegundos(HORIZONTAL), s.verdeSegundos(VERTICAL), 1e-9);
    }

    @Test
    void unCruceMuyCargadoObtieneUnCicloMasLargo() {
        EberthMSemaforo tranquilo = new EberthMSemaforo(1);
        tranquilo.setDemanda(2, 2);
        EberthMSemaforo cargado = new EberthMSemaforo(1);
        cargado.setDemanda(12, 12); // más de 10 rutas por eje
        assertEquals(EberthMSemaforo.CICLO_MINIMO_SEGUNDOS, tranquilo.cicloSegundos(), 1e-9);
        assertEquals(EberthMSemaforo.CICLO_MAXIMO_SEGUNDOS, cargado.cicloSegundos(), 1e-9);
        assertTrue(cargado.verdeSegundos(HORIZONTAL) > tranquilo.verdeSegundos(HORIZONTAL));
    }

    @Test
    void alCambiarLaDemandaElCicloSeRecalcula() {
        EberthMSemaforo s = new EberthMSemaforo(1);
        s.setDemanda(2, 2);
        double antes = s.verdeSegundos(VERTICAL);
        s.setDemanda(2, 4);
        assertTrue(s.verdeSegundos(VERTICAL) > antes);
    }

    @Test
    void enHoraPuntaElCicloSeAcortaUnVeinticincoPorCiento() {
        EberthMSemaforo s = new EberthMSemaforo(1);
        s.setDemanda(2, 1);
        double normal = s.cicloSegundos();
        s.setHoraPunta(true);
        assertEquals(normal * EberthMSemaforo.FACTOR_CICLO_HORA_PUNTA, s.cicloSegundos(), 1e-9);
        assertTrue(s.verdeSegundos(HORIZONTAL) > s.verdeSegundos(VERTICAL));
    }

    @Test
    void sinDemandaConocidaElCicloEsDe60ConVerdesIguales() {
        EberthMSemaforo s = new EberthMSemaforo(1);
        s.setDemanda(0, 0);
        assertEquals(60, s.cicloSegundos(), 1e-9);
        assertEquals(30, s.verdeSegundos(HORIZONTAL), 1e-9);
    }

    @Test
    void aVelocidadAltaLosTiemposSeDuplican() {
        EberthMSemaforo s = new EberthMSemaforo(1);
        s.setDemanda(1, 1);
        double ciclo = s.cicloSegundos();
        s.setEscalaTiempo(EberthMSemaforo.ESCALA_CICLO_LARGO);
        assertEquals(2 * ciclo, s.cicloSegundos(), 1e-9);
        assertEquals(ciclo, s.verdeSegundos(HORIZONTAL), 1e-9);
    }

    @Test
    void nuncaHayVerdeEnLosDosEjesAlMismoTiempo() {
        EberthMSemaforo s = new EberthMSemaforo(7);
        s.setDemanda(5, 2);
        for (double t = 0; t < 180; t += 0.5) {
            boolean h = s.estadoEn(t, HORIZONTAL, false) == EberthMSemaforo.Estado.VERDE;
            boolean v = s.estadoEn(t, VERTICAL, false) == EberthMSemaforo.Estado.VERDE;
            assertFalse(h && v, "t=" + t);
        }
    }

    @Test
    void conPoliciaElRojoDuraLaMitad() {
        EberthMSemaforo s = new EberthMSemaforo(3);
        s.setDemanda(1, 1);
        int rojoNormal = 0, rojoPolicia = 0;
        int ciclo = (int) s.cicloSegundos();
        for (int t = 0; t < ciclo; t++) {
            if (s.esRojo(t, HORIZONTAL, false)) rojoNormal++;
            if (s.esRojo(t, HORIZONTAL, true)) rojoPolicia++;
        }
        assertEquals(ciclo / 2, rojoNormal);
        assertEquals(ciclo / 4, rojoPolicia);
    }
}
