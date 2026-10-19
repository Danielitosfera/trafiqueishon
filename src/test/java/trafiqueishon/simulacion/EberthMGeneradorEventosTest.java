package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;
import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGCiudadMapa;
import trafiqueishon.geo.PieroGNodo;

import java.util.List;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

class EberthMGeneradorEventosTest {

    private static PieroGEdge via(int id, String highway) {
        return new PieroGEdge(id, new PieroGNodo(id, 0, 0, -70 + id * 0.001, -15),
                new PieroGNodo(id + 100, 1, 0, -69.999 + id * 0.001, -15), 100, highway,
                "Calle " + id, 30, false, 1, null);
    }

    /** Siempre devuelve el mismo valor: 0 fuerza todos los sorteos, 0.99 los impide. */
    private static RandomGenerator fijo(double valor) {
        return new RandomGenerator() {
            @Override public long nextLong() { return 0; }
            @Override public double nextDouble() { return valor; }
            @Override public int nextInt(int limite) { return 0; }
        };
    }

    @Test
    void horaPuntaTriplicaLaProbabilidadDeTrancadera() {
        assertEquals(3 * EberthMGeneradorEventos.probabilidadTrancadera(false),
                EberthMGeneradorEventos.probabilidadTrancadera(true), 1e-12);
    }

    @Test
    void tasaAltaDeAccidentesEsMayorQueMediaYBaja() {
        var t = EberthMParametrosCiudad.TasaAccidentes.values();
        assertTrue(EberthMGeneradorEventos.probabilidadAccidente(t[0])
                > EberthMGeneradorEventos.probabilidadAccidente(t[1]));
        assertTrue(EberthMGeneradorEventos.probabilidadAccidente(t[1])
                > EberthMGeneradorEventos.probabilidadAccidente(t[2]));
    }

    @Test
    void soloUsaViasPrincipalesYSecundariasYCaducan() {
        var gen = new EberthMGeneradorEventos(fijo(0));
        PieroGEdge residencial = via(1, "residential");
        PieroGEdge principal = via(2, "primary");
        gen.setVias(List.of(residencial, principal));
        var ciudad = EberthMParametrosCiudad.porDefecto(PieroGCiudadMapa.JULIACA);

        var nuevos = gen.generar(1, true, ciudad);

        assertEquals(3, nuevos.size());
        double lonEsperada = (principal.origen.lon + principal.destino.lon) / 2;
        assertTrue(nuevos.stream().allMatch(e -> e.lon() == lonEsperada));
        var lluvia = nuevos.stream().filter(e -> e.tipo() == EberthMTipoEvento.LLUVIA).findFirst().orElseThrow();
        assertTrue(lluvia.radioMetros() >= 500 && lluvia.radioMetros() <= 2000);
        assertTrue(gen.expirados(2).isEmpty());
        assertEquals(3, gen.expirados(1 + 40).size());
    }

    @Test
    void respetaElMaximoDeEventosSimultaneosYNadaSinSuerte() {
        var gen = new EberthMGeneradorEventos(fijo(0));
        gen.setVias(List.of(via(1, "secondary")));
        var ciudad = EberthMParametrosCiudad.porDefecto(PieroGCiudadMapa.LIMA);
        for (int m = 1; m <= 20; m++) gen.generar(m, true, ciudad);
        assertTrue(gen.generar(21, true, ciudad).isEmpty());

        var sinSuerte = new EberthMGeneradorEventos(fijo(0.99));
        sinSuerte.setVias(List.of(via(1, "primary")));
        assertTrue(sinSuerte.generar(1, true, ciudad).isEmpty());
    }
}
