package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpatialGridTest {
    @Test
    void returnsOnlyAgentsInsideTheRadius() {
        SpatialGrid grid = new SpatialGrid(10, 10);
        grid.reconstruir(new float[]{0.10f, 0.12f, 0.90f},
                new float[]{0.10f, 0.10f, 0.90f}, 3);
        List<Integer> cercanos = new ArrayList<>();
        grid.paraCadaCercano(0.10f, 0.10f, 0.03f, cercanos::add);
        assertEquals(List.of(0, 1), cercanos.stream().sorted().toList());
    }

    @Test
    void emitsEachPotentialCollisionPairOnlyOnce() {
        SpatialGrid grid = new SpatialGrid(10, 10);
        grid.reconstruir(new float[]{0.10f, 0.12f, 0.90f},
                new float[]{0.10f, 0.10f, 0.90f}, 3);
        List<String> pares = new ArrayList<>();
        grid.paraCadaParCercano(0.03f, (a, b) -> pares.add(a + ":" + b));
        assertEquals(List.of("0:1"), pares);
    }
}
