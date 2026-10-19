package trafiqueishon.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LuisYFormatoCantidadTest {
    @Test
    void abreviaMilesYMillones() {
        assertEquals("0", LuisYFormatoCantidad.abreviar(0));
        assertEquals("999", LuisYFormatoCantidad.abreviar(999));
        assertEquals("1k", LuisYFormatoCantidad.abreviar(1000));
        assertEquals("1.5k", LuisYFormatoCantidad.abreviar(1500));
        assertEquals("10k", LuisYFormatoCantidad.abreviar(10_000));
        assertEquals("50k", LuisYFormatoCantidad.abreviar(50_000));
        assertEquals("1M", LuisYFormatoCantidad.abreviar(999_999));
        assertEquals("2.3M", LuisYFormatoCantidad.abreviar(2_300_000));
    }
}
