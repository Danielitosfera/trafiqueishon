package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MatrizDemandaODTest {
    @Test
    void kMeansDemandMatrixNormalizesDestinationsAndExcludesSelfTrips() {
        List<PuntoInteres> puntos = List.of(
                new PuntoInteres("residencial", "Residencial", -70.14, -15.50, 4),
                new PuntoInteres("mercado", "Mercado", -70.13, -15.50, 8),
                new PuntoInteres("terminal", "Terminal", -70.12, -15.49, 6));
        MatrizDemandaOD matriz = new MatrizDemandaOD(puntos, 2);

        assertEquals(1.0, matriz.probabilidad("residencial", "mercado")
                + matriz.probabilidad("residencial", "terminal"), 1e-9);
        assertEquals(0.0, matriz.probabilidad("residencial", "residencial"));
        assertNotEquals("residencial",
                matriz.elegirDestino("residencial", new Random(9)).id());
    }
}
