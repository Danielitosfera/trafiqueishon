package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;
import trafiqueishon.geo.PieroGCiudadMapa;
import trafiqueishon.ruta.LuisYPuntoImportante.TipoPunto;

import static org.junit.jupiter.api.Assertions.*;
import static trafiqueishon.simulacion.EberthMCategoriaPoi.*;
import static trafiqueishon.simulacion.EberthMTipoDia.*;

class EberthMPerfilDemandaTest {

    private final EberthMParametrosCiudad ciudad = new EberthMParametrosCiudad(100_000, 60,
            "07:00-09:00", 20, EberthMParametrosCiudad.TasaAccidentes.MEDIA, 25, 50, 25, 150);

    private double f(EberthMCategoriaPoi c, int hora, EberthMTipoDia dia) {
        return EberthMPerfilDemanda.factor(c, hora * 60, dia, ciudad);
    }

    @Test
    void cadaFranjaFavoreceASusCategoriasEnDiaLaboral() {
        assertTrue(f(EDUCACION, 7, LABORAL) > f(COMERCIO, 7, LABORAL));
        assertTrue(f(TRABAJO, 7, LABORAL) > f(SALUD, 7, LABORAL));
        assertTrue(f(COMERCIO, 12, LABORAL) > f(EDUCACION, 12, LABORAL));
        assertTrue(f(SALUD, 12, LABORAL) > f(TRABAJO, 12, LABORAL));
        assertTrue(f(TRABAJO, 18, LABORAL) > f(SALUD, 18, LABORAL));
        assertTrue(f(COMERCIO, 18, LABORAL) > f(TURISMO, 18, LABORAL));
    }

    @Test
    void lasNochesTienenDemandaCasiNula() {
        for (EberthMCategoriaPoi c : EberthMCategoriaPoi.values()) {
            assertEquals(0.1, f(c, 23, LABORAL), 1e-9);
            assertEquals(0.1, f(c, 3, LABORAL), 1e-9);
        }
    }

    @Test
    void losPorcentajesDemograficosEscalanLaDemanda() {
        double base = f(EDUCACION, 7, LABORAL);
        var mas = ciudad.copia();
        mas.setDemografia(50, 30, 20);
        assertEquals(base * 2, EberthMPerfilDemanda.factor(EDUCACION, 7 * 60, LABORAL, mas), 1e-9);
    }

    @Test
    void elFinDeSemanaVaciaColegiosYOficinasYSubeTurismo() {
        assertTrue(f(EDUCACION, 7, DOMINGO) < f(EDUCACION, 7, LABORAL) * 0.05);
        assertTrue(f(TRABAJO, 7, SABADO) < f(TRABAJO, 7, LABORAL) * 0.5);
        assertTrue(f(TURISMO, 12, DOMINGO) > f(TURISMO, 12, LABORAL));
        // picos aplanados: el máximo del domingo es mucho menor que el del lunes
        assertTrue(f(COMERCIO, 18, DOMINGO) < f(COMERCIO, 18, LABORAL));
    }

    @Test
    void elPerfilGeneralConservaElComportamientoLaboralYReduceElFinDeSemana() {
        assertEquals(1.0,
                EberthMPerfilDemanda.factorGeneral(10 * 60, LABORAL), 1e-9);
        assertEquals(0.70, EberthMPerfilDemanda.factorGeneral(10 * 60, SABADO), 1e-9);
        assertTrue(EberthMPerfilDemanda.factorGeneral(18 * 60, FERIADO)
                < EberthMPerfilDemanda.factorGeneral(18 * 60, LABORAL));
    }

    @Test
    void mapeaLosTiposDePuntoALasCategorias() {
        assertEquals(EDUCACION, desde(TipoPunto.EDUCACION));
        assertEquals(COMERCIO, desde(TipoPunto.MERCADO));
        assertEquals(SALUD, desde(TipoPunto.SALUD));
        assertEquals(TURISMO, desde(TipoPunto.PARQUE));
        assertEquals(TRABAJO, desde(TipoPunto.INSTITUCION));
        assertEquals(OTRO, desde(TipoPunto.PARADA));
    }

    @Test
    void horaPuntaYAccidentesDependenDelTipoDia() {
        var p = EberthMParametrosCiudad.porDefecto(PieroGCiudadMapa.JULIACA);
        assertTrue(p.esHoraPunta(7 * 60 + 30, LABORAL));
        assertFalse(p.esHoraPunta(7 * 60 + 30, DOMINGO));
        assertTrue(p.esHoraPunta(12 * 60, SABADO));
        var tasa = EberthMParametrosCiudad.TasaAccidentes.MEDIA;
        assertTrue(EberthMGeneradorEventos.probabilidadAccidente(tasa, FERIADO)
                > EberthMGeneradorEventos.probabilidadAccidente(tasa, LABORAL));
        assertTrue(EberthMGeneradorEventos.probabilidadAccidente(tasa, DOMINGO)
                < EberthMGeneradorEventos.probabilidadAccidente(tasa, LABORAL));
    }

    private static EberthMCategoriaPoi desde(TipoPunto t) {
        return EberthMCategoriaPoi.desde(t);
    }
}
