package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModeloIDMTest {
    @Test
    void slowsDownWhenFollowingVehicleGetsCloser() {
        double near = ModeloIDM.aceleracion(12, 8, 8, 20, 1.2, 2.5, 1.1, 2.0);
        double far = ModeloIDM.aceleracion(12, 8, 40, 20, 1.2, 2.5, 1.1, 2.0);
        assertTrue(near < far);
        assertTrue(near < 0);
    }

    @Test
    void acceleratesTowardDesiredSpeedWithoutLeader() {
        double acceleration = ModeloIDM.aceleracion(
                5, 0, Double.POSITIVE_INFINITY, 15, 1.2, 2.5, 1.1, 2.0);
        assertTrue(acceleration > 0);
    }
}
