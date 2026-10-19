package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;
import trafiqueishon.io.BetoHServicioPuntos;
import trafiqueishon.ruta.LuisYPuntoImportante;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EberthMGestorServiciosTest {
    @Test
    void siguienteColorEmpiezaPorLaPaletaSinServicios() {
        assertEquals("#39FF14", EberthMGestorServicios.siguienteColor(List.of()));
    }

    @Test
    void indiceDeNoEncontradoEsMenosUno() {
        assertEquals(-1, EberthMGestorServicios.indiceDe(List.of(), "x"));
    }

    @Test
    void nuevoIdSaltaLosUsados() {
        var p1 = new LuisYPuntoImportante("p1", "A", -70.1, -15.5,
                LuisYPuntoImportante.TipoPunto.PARADA);
        assertEquals("p2", BetoHServicioPuntos.nuevoId(List.of(p1)));
        assertEquals(-70.13, BetoHServicioPuntos.centroPorDefecto(null)[0]);
    }
}
