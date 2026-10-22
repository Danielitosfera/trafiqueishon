package trafiqueishon.simulacion;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.geo.PieroGProyector;
import trafiqueishon.geo.PieroGVia;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.ruta.LuisYPuntoImportante.TipoPunto;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Troncales y alimentadoras sobre una ciudad de rejilla de 16 × 16 km. */
class EberthMRedTroncalTest {

    private static final double PASO = 0.0009; // ≈ 100 m
    private static final int N = 160;
    private static final double LON0 = -70.2, LAT0 = -15.6;

    private static PieroGGrafoVial red;
    private static List<LuisYPuntoImportante> puntos;
    private static EberthMDemandaCiudad demanda;

    @BeforeAll
    static void ciudad() {
        List<PieroGVia> vias = new ArrayList<>();
        for (int i = 0; i < N; i += 2) {
            PieroGVia h = via("h" + i), v = via("v" + i);
            for (int j = 0; j < N; j++) {
                h.coords.add(new double[]{LON0 + j * PASO, LAT0 + i * PASO});
                v.coords.add(new double[]{LON0 + i * PASO, LAT0 + j * PASO});
            }
            vias.add(h);
            vias.add(v);
        }
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(vias);
        red = new PieroGGrafoVial();
        red.construir(vias, proyector);

        // Colegios por toda la ciudad y, más concentrados hacia el centro, mercados y hospitales.
        puntos = new ArrayList<>();
        Random azar = new Random(3);
        for (int i = 0; i < 120; i++) {
            puntos.add(punto("colegio" + i, TipoPunto.EDUCACION, 10 + azar.nextInt(N - 20), 10 + azar.nextInt(N - 20)));
        }
        for (int i = 0; i < 12; i++) {
            puntos.add(punto("mercado" + i, i % 2 == 0 ? TipoPunto.MERCADO : TipoPunto.SALUD,
                    35 + azar.nextInt(N - 70), 35 + azar.nextInt(N - 70)));
        }
        List<EberthMPoi> pois = puntos.stream().map(p -> new EberthMPoi(p.nombre(), p.lon(), p.lat(),
                EberthMCategoriaPoi.desde(p.tipo()))).toList();
        demanda = EberthMDemandaCiudad.generar(red, pois, EberthMParametrosCiudad.porDefecto(
                trafiqueishon.geo.PieroGCiudadMapa.JULIACA), EberthMTipoDia.LABORAL);
    }

    private static List<EberthMServicioMicro> generar(int rutas, EberthMGeneradorRutas.TiposRuta tipos) {
        return new EberthMGeneradorRutas(red, puntos, EberthMParametrosCiudad.porDefecto(
                trafiqueishon.geo.PieroGCiudadMapa.JULIACA), new Random(11))
                .generarDesdeDemanda(demanda, List.of(),
                        new EberthMGeneradorRutas.Opciones(rutas, 5, 0, 10, 5, 0, tipos));
    }

    private static boolean esTroncal(EberthMServicioMicro r) {
        return r.nombre().contains("Troncal");
    }

    @Test
    void lasTroncalesVanDeUnExtremoAOtroConAlMenosOchoKilometros() {
        var rutas = generar(5, EberthMGeneradorRutas.TiposRuta.TRONCALES);
        assertEquals(5, rutas.size());
        for (var r : rutas) {
            assertTrue(esTroncal(r));
            double km = r.trayecto().distanciaMetros() / 1000;
            assertTrue(km >= 8 && km <= 12.6, r.nombre() + ": " + km + " km");
        }
    }

    @Test
    void lasAlimentadorasMidenEntreCuatroYSeisKilometrosYMedio() {
        var rutas = generar(4, EberthMGeneradorRutas.TiposRuta.ALIMENTADORAS);
        assertFalse(rutas.isEmpty());
        for (var r : rutas) {
            double km = r.trayecto().distanciaMetros() / 1000;
            assertTrue(km >= 4 && km <= 6.8, r.nombre() + ": " + km + " km");
        }
    }

    @Test
    void conAmbosTiposSeReparte80Troncales20Alimentadoras() {
        var rutas = generar(10, EberthMGeneradorRutas.TiposRuta.AMBAS);
        assertEquals(10, rutas.size());
        assertEquals(8, rutas.stream().filter(EberthMRedTroncalTest::esTroncal).count());
        assertEquals(2, rutas.stream().filter(r -> !esTroncal(r)).count());
    }

    @Test
    void ningunaRutaBajaDeCuatroKilometrosYTodasPasanPorPois() {
        var rutas = generar(10, EberthMGeneradorRutas.TiposRuta.AMBAS);
        for (var r : rutas) {
            assertTrue(r.trayecto().distanciaMetros() >= 4_000, r.nombre());
            boolean[][] alcance = EberthMCoberturaDemanda.alcance(demanda, r.trayecto().edges());
            int pois = 0;
            for (boolean cerca : alcance[1]) if (cerca) pois++;
            assertTrue(pois >= EberthMGeneradorRutas.POIS_MINIMOS_RUTA, r.nombre() + ": " + pois);
            assertTrue(r.trayecto().puntos().size() >= 5, r.nombre());
        }
    }

    @Test
    void cadaRutaNuevaAmpliaLaCoberturaDeLaDemanda() {
        var rutas = generar(8, EberthMGeneradorRutas.TiposRuta.AMBAS);
        double[] cobertura = EberthMCoberturaDemanda.coberturaAcumulada(demanda, List.of(), rutas);
        for (int k = 1; k < cobertura.length; k++) assertTrue(cobertura[k] > cobertura[k - 1], "ruta " + k);
        assertTrue(cobertura[cobertura.length - 1] > 0.15);
    }

    @Test
    void laFlotaCreceConLaLongitudYLaDemandaYMantieneLaFrecuencia() {
        // Misma demanda: una vuelta el doble de larga necesita el doble de unidades para la frecuencia.
        assertEquals(6, EberthMGeneradorRutas.unidadesParaRuta(0, 60, 10, 5));
        assertEquals(12, EberthMGeneradorRutas.unidadesParaRuta(0, 120, 10, 5));
        // Más demanda, más unidades, hasta lo que cabe a la frecuencia de punta (vuelta ÷ 5 min).
        assertTrue(EberthMGeneradorRutas.unidadesParaRuta(150, 60, 10, 5)
                > EberthMGeneradorRutas.unidadesParaRuta(0, 60, 10, 5));
        assertEquals(12, EberthMGeneradorRutas.unidadesParaRuta(10_000, 60, 10, 5));
        // Las rutas largas de la ciudad llevan más unidades que el mínimo.
        for (var r : generar(3, EberthMGeneradorRutas.TiposRuta.TRONCALES)) {
            assertTrue(r.unidadesDisponibles() > EberthMGeneradorRutas.MIN_UNIDADES,
                    r.nombre() + ": " + r.unidadesDisponibles() + " unidades, " + r.trayecto().resumen());
        }
    }

    @Test
    void conMapaDemasiadoPequenoNoInventaRutasCortas() {
        // Con las longitudes reales una ciudad de 16 km sí da rutas; escalada a 0 km no daría ninguna ≥ 4 km.
        var generador = new EberthMGeneradorRutas(red, puntos, null, new Random(1));
        generador.setLongitudes(new EberthMGeneradorRutas.Longitudes(80_000, 90_000, 70_000, 80_000, 70_000));
        assertTrue(generador.generarDesdeDemanda(demanda, List.of(),
                new EberthMGeneradorRutas.Opciones(3, 5, 0, 10, 5)).isEmpty());
    }

    @Test
    void lasRutasTienenParadasEnLasZonasDeOrigenDeLaDemanda() {
        var rutas = generar(4, EberthMGeneradorRutas.TiposRuta.TRONCALES);
        for (var r : rutas) {
            var paradas = r.trayecto().puntos();
            assertTrue(paradas.size() > 8, r.nombre() + ": " + paradas.size() + " paradas");
            assertTrue(paradas.size() <= EberthMGeneradorRutas.MAX_PARADAS_RUTA);
            // Cada zona con demanda junto al trazado (≤ 200 m) tiene una parada a ≤ 300 m: allí sale la gente.
            int junto = 0, atendidas = 0;
            for (var z : demanda.zonas()) {
                if (!EberthMCoberturaDemanda.cerca(z.lon(), z.lat(), r.trayecto().edges(), 200)) continue;
                junto++;
                for (var p : paradas) {
                    double dx = (p.lon() - z.lon()) * 111_320 * Math.cos(Math.toRadians(z.lat()));
                    if (Math.hypot(dx, (p.lat() - z.lat()) * 111_320) <= 300) {
                        atendidas++;
                        break;
                    }
                }
            }
            assertTrue(junto > 0 && atendidas >= 0.8 * junto, r.nombre() + ": " + atendidas + "/" + junto);
        }
    }

    @Test
    void laSimulacionInyectaLaDemandaDeLaCiudadYLaGenteSube() {
        var ciudad = EberthMParametrosCiudad.porDefecto(trafiqueishon.geo.PieroGCiudadMapa.JULIACA);
        var rutas = generar(6, EberthMGeneradorRutas.TiposRuta.AMBAS);
        var sim = new EberthMSimuladorBuses();
        sim.setEjecutorDesvios(Runnable::run);
        sim.configurarGrafo(red);
        sim.setPuntosInteres(demanda.pois());
        sim.setParametrosCiudad(ciudad);
        sim.setDemandaCiudad(demanda);
        sim.setEscalaDemanda((int) EberthMParametrosCiudad.ESCALA_POBLACION_SIMULADA);
        sim.iniciar(rutas, 7, 0);
        for (int s = 0; s < 15 * 60; s++) sim.avanzar(1.0);
        // En punta la demanda real son ≈ 250 viajes por minuto en toda la ciudad (con escala ×10).
        long inyectados = sim.pasajerosGenerados() + sim.pasajerosSinCobertura();
        double porMinuto = demanda.viajesPorHora()[7] / 60.0;
        assertEquals(porMinuto * 15, inyectados, porMinuto * 15 * 0.15);
        assertTrue(sim.pasajerosGenerados() > 100, "generados: " + sim.pasajerosGenerados());
        assertTrue(sim.pasajerosTransportados() > 0, "nadie subió");
    }

    private static LuisYPuntoImportante punto(String nombre, TipoPunto tipo, int x, int y) {
        return new LuisYPuntoImportante(nombre, nombre, LON0 + x * PASO, LAT0 + y * PASO, tipo);
    }

    private static PieroGVia via(String nombre) {
        PieroGVia via = new PieroGVia();
        via.id = nombre;
        via.name = nombre;
        via.highway = "residential";
        via.coords = new ArrayList<>();
        return via;
    }
}
