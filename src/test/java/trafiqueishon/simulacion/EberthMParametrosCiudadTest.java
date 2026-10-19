package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;
import trafiqueishon.geo.PieroGCiudadMapa;

import static org.junit.jupiter.api.Assertions.*;

class EberthMParametrosCiudadTest {

    @Test
    void cadaCiudadTieneValoresPorDefectoPropios() {
        var juliaca = EberthMParametrosCiudad.porDefecto(PieroGCiudadMapa.JULIACA);
        var lima = EberthMParametrosCiudad.porDefecto(PieroGCiudadMapa.LIMA);
        assertTrue(lima.getPoblacionTotal() > juliaca.getPoblacionTotal());
        assertTrue(juliaca.getProbabilidadLluvia() > lima.getProbabilidadLluvia());
        assertEquals(EberthMParametrosCiudad.TasaAccidentes.ALTA, lima.getTasaAccidentes());
    }

    @Test
    void horasPuntaAdmiteVariosRangosYCruceDeMedianoche() {
        var p = EberthMParametrosCiudad.porDefecto(PieroGCiudadMapa.PUNO);
        p.setHorasPunta("07:00-09:00, 22:00-01:00");
        assertTrue(p.esHoraPunta(7 * 60));
        assertFalse(p.esHoraPunta(9 * 60));
        assertTrue(p.esHoraPunta(23 * 60));
        assertTrue(p.esHoraPunta(30));
        assertFalse(p.esHoraPunta(12 * 60));
    }

    @Test
    void rechazaValoresInvalidos() {
        var p = EberthMParametrosCiudad.porDefecto(PieroGCiudadMapa.JULIACA);
        assertThrows(IllegalArgumentException.class, () -> p.setHorasPunta("7 a 9"));
        assertThrows(IllegalArgumentException.class, () -> p.setHorasPunta("25:00-26:00"));
        assertThrows(IllegalArgumentException.class, () -> p.setProbabilidadLluvia(120));
        assertThrows(IllegalArgumentException.class, () -> p.setPoblacionTotal(-1));
    }

    @Test
    void demografiaDebeSumarCienYLaCapacidadTieneRango() {
        var p = EberthMParametrosCiudad.porDefecto(PieroGCiudadMapa.PUNO);
        assertEquals(100, p.getPorcentajeEstudiantes() + p.getPorcentajeTrabajadores()
                + p.getPorcentajeJubilados(), 1e-9);
        assertEquals(150, p.getCapacidadMaximaParada());
        assertThrows(IllegalArgumentException.class, () -> p.setDemografia(30, 50, 30));
        assertEquals(28, p.getPorcentajeEstudiantes()); // el fallo no deja valores a medias
        assertThrows(IllegalArgumentException.class, () -> p.setCapacidadMaximaParada(0));
        p.setDemografia(10, 60, 30);
        assertEquals(p.getPorcentajeTrabajadores(), p.copia().getPorcentajeTrabajadores());
    }
}
