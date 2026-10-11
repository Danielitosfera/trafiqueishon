package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;
import trafiqueishon.geo.Edge;
import trafiqueishon.geo.Nodo;

import static org.junit.jupiter.api.Assertions.*;

class SemaforoInteligenteTest {
    @Test
    void switchesApproachPhaseAndKeepsGreenTimeBounded() {
        Nodo cruce = new Nodo(0, 0.5, 0.5, -70, -15);
        Nodo accesoEste = new Nodo(1, 0.4, 0.5, -70.1, -15);
        Edge ejeHorizontal = new Edge(0, accesoEste, cruce, 50,
                "primary", "Av. Principal", 40, false, 2, null);
        SemaforoInteligente semaforo = new SemaforoInteligente(cruce, 7);

        assertTrue(semaforo.permite(ejeHorizontal));
        semaforo.actualizar(25, 12, 0);

        assertFalse(semaforo.permite(ejeHorizontal));
        assertTrue(semaforo.duracionVerde() >= 8);
        assertTrue(semaforo.duracionVerde() <= 45);
    }
}
