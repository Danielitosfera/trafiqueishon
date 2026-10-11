package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PoolVehiculosTest {
    @Test
    void reusesTheDenseTailSlotAfterRemoval() {
        PoolVehiculos pool = new PoolVehiculos();
        assertEquals(0, pool.adquirir(3));
        assertEquals(1, pool.adquirir(3));
        assertEquals(2, pool.adquirir(3));
        assertEquals(2, pool.liberar(0));
        assertEquals(2, pool.activos());
        assertEquals(2, pool.adquirir(3));
    }

    @Test
    void rejectsAcquisitionPastCapacity() {
        PoolVehiculos pool = new PoolVehiculos();
        pool.adquirir(1);
        assertThrows(IllegalStateException.class, () -> pool.adquirir(1));
    }
}
