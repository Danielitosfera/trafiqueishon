package trafiqueishon.geo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PieroGProyectorTest {
    @Test
    void usaCoordenadasDeRenderFloatSinPerderExtremos() {
        PieroGVia via = new PieroGVia();
        via.coords = List.of(new double[]{-70.0, -15.0},
                new double[]{-69.9, -14.9});

        new PieroGProyector().preProyectar(List.of(via));

        assertEquals(0f, via.projX[0]);
        assertEquals(1f, via.projX[1]);
        assertEquals(1f, via.projY[0]);
        assertEquals(0f, via.projY[1]);
    }
}
