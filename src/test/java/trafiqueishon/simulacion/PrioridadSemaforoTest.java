package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrioridadSemaforoTest {
    @Test
    void ignoresLowDegreeNodesAndScoresDemandAndHierarchy() {
        assertEquals(0, PrioridadSemaforo.puntaje(2, 3, 5));
        double arterial = PrioridadSemaforo.puntaje(4, 2.5, 0.5);
        double secondary = PrioridadSemaforo.puntaje(4, 1.5, 0.5);
        assertTrue(arterial > secondary);
        assertTrue(PrioridadSemaforo.superaUmbral(arterial));
        assertFalse(PrioridadSemaforo.superaUmbral(secondary));
    }
}
