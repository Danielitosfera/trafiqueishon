package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;
import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.geo.PieroGProyector;
import trafiqueishon.geo.PieroGVia;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EberthMDemandaCiudadTest {

    private static PieroGGrafoVial rejilla() {
        List<PieroGVia> vias = new ArrayList<>();
        double lon0 = -70.15, lat0 = -15.50, paso = 0.0009;
        for (int i = 0; i <= 20; i++) {
            List<double[]> h = new ArrayList<>(), v = new ArrayList<>();
            for (int j = 0; j <= 20; j++) {
                h.add(new double[]{lon0 + j * paso, lat0 + i * paso});
                v.add(new double[]{lon0 + i * paso, lat0 + j * paso});
            }
            vias.add(via("c" + i, h));
            vias.add(via("a" + i, v));
        }
        PieroGGrafoVial g = new PieroGGrafoVial();
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(vias);
        g.construir(vias, proyector);
        return g;
    }

    /** La cuadrícula de pruebas mide 2 km: se escalan las longitudes mínimas de las rutas. */
    private static EberthMGeneradorRutas pequeno(EberthMGeneradorRutas generador) {
        generador.setLongitudes(EberthMGeneradorRutas.Longitudes.REALES.escalada(0.1));
        return generador;
    }

    private static PieroGVia via(String id, List<double[]> coords) {
        PieroGVia via = new PieroGVia();
        via.id = id;
        via.highway = "residential";
        via.name = id;
        via.coords = coords;
        return via;
    }

    private static List<EberthMPoi> pois() {
        return List.of(
                new EberthMPoi("Colegio X", -70.145, -15.495, EberthMCategoriaPoi.EDUCACION),
                new EberthMPoi("Hospital Y", -70.140, -15.490, EberthMCategoriaPoi.SALUD),
                new EberthMPoi("Mercado Z", -70.141, -15.498, EberthMCategoriaPoi.COMERCIO));
    }

    @Test
    void totalYPicoCoincidenConLosParametros() {
        EberthMParametrosCiudad ciudad = new EberthMParametrosCiudad(280_000, 65, "07:00-09:00", 20,
                EberthMParametrosCiudad.TasaAccidentes.MEDIA);
        EberthMDemandaCiudad d = EberthMDemandaCiudad.generar(rejilla(), pois(), ciudad, EberthMTipoDia.LABORAL);
        assertEquals(182_000, d.totalViajes(), 2);
        assertTrue(d.horaPico() == 7 || d.horaPico() == 8 || d.horaPico() == 17 || d.horaPico() == 18
                || d.horaPico() == 19);
        var destinos = d.topDestinos(3);
        assertTrue(destinos.get(0).viajes() >= destinos.get(1).viajes());
        assertEquals(d.totalViajes(), destinos.stream().mapToLong(c -> c.viajes()).sum(), 5);
        assertEquals(d.totalViajes(), d.topOrigenes(10_000).stream().mapToLong(c -> c.viajes()).sum(), 50);
        assertFalse(d.flujosHacia(0).isEmpty());
        assertTrue(d.puntosGente(8).length > 0);
    }

    @Test
    void sinPoisFalla() {
        assertThrows(IllegalArgumentException.class,
                () -> EberthMDemandaCiudad.generar(rejilla(), List.of(), null, null));
    }

    @Test
    void laOfertaNaceDeLaDemandaYTerminaEnUnDestinoPopular() {
        PieroGGrafoVial g = rejilla();
        List<EberthMPoi> pois = pois();
        EberthMDemandaCiudad d = EberthMDemandaCiudad.generar(g, pois, null, EberthMTipoDia.LABORAL);
        List<trafiqueishon.ruta.LuisYPuntoImportante> puntos = new ArrayList<>();
        int i = 0;
        for (EberthMPoi p : pois) {
            puntos.add(new trafiqueishon.ruta.LuisYPuntoImportante("p" + i++, p.nombre(), p.lon(), p.lat(),
                    trafiqueishon.ruta.LuisYPuntoImportante.TipoPunto.EDUCACION));
        }
        var generador = new EberthMGeneradorRutas(g, puntos, null, new java.util.Random(1));
        generador.setLongitudes(EberthMGeneradorRutas.Longitudes.REALES.escalada(0.1));
        var rutas = generador.generarDesdeDemanda(d, List.of(), new EberthMGeneradorRutas.Opciones(2, 4, 2, 10, 5));
        assertFalse(rutas.isEmpty());
    }

    @Test
    void losDestinosDeUnaZonaSiguenElFlujoYNoElAzar() {
        EberthMDemandaCiudad d = EberthMDemandaCiudad.generar(rejilla(), pois(), null, EberthMTipoDia.LABORAL);
        int zona = d.zonaMayorOrigen();
        var flujos = d.destinosDesdeZona(zona);
        double total = flujos.stream().mapToDouble(f -> f.viajes()).sum();
        int[] cuenta = new int[d.pois().size()];
        java.util.Random azar = new java.util.Random(5);
        for (int i = 0; i < 20_000; i++) cuenta[d.sortearPoi(zona, azar)]++;
        for (var f : flujos) {
            assertEquals(f.viajes() / total, cuenta[f.poi()] / 20_000.0, 0.02);
        }
        assertTrue(d.resumenZona(zona).startsWith("Se generaron"));
    }

    @Test
    void sinRutasLaCoberturaEsCeroYAvisaQueFaltanRutas() {
        EberthMDemandaCiudad d = EberthMDemandaCiudad.generar(rejilla(), pois(), null, EberthMTipoDia.LABORAL);
        var r = EberthMCoberturaDemanda.evaluar(d, List.of());
        assertEquals(0, r.porcentaje(), 1e-9);
        assertFalse(r.advertencias().isEmpty());
    }

    @Test
    void cubrirObjetivoAnadeRutasSinReducirLaCobertura() {
        PieroGGrafoVial g = rejilla();
        List<EberthMPoi> pois = pois();
        EberthMDemandaCiudad d = EberthMDemandaCiudad.generar(g, pois, null, EberthMTipoDia.LABORAL);
        List<trafiqueishon.ruta.LuisYPuntoImportante> puntos = new ArrayList<>();
        int i = 0;
        for (EberthMPoi p : pois) {
            puntos.add(new trafiqueishon.ruta.LuisYPuntoImportante("p" + i++, p.nombre(), p.lon(), p.lat(),
                    trafiqueishon.ruta.LuisYPuntoImportante.TipoPunto.EDUCACION));
        }
        var simples = pequeno(new EberthMGeneradorRutas(g, puntos, null, new java.util.Random(1)))
                .generarDesdeDemanda(d, List.of(), new EberthMGeneradorRutas.Opciones(1, 4, 2, 10, 5, 0));
        var ampliadas = pequeno(new EberthMGeneradorRutas(g, puntos, null, new java.util.Random(1)))
                .generarDesdeDemanda(d, List.of(), new EberthMGeneradorRutas.Opciones(1, 4, 2, 10, 5, 0.8));
        assertFalse(simples.isEmpty());
        assertTrue(ampliadas.size() >= simples.size());
        assertTrue(EberthMCoberturaDemanda.evaluar(d, ampliadas).porcentaje()
                >= EberthMCoberturaDemanda.evaluar(d, simples).porcentaje() - 1e-9);
    }

    @Test
    void elPlanDaLaCoberturaDeCadaCantidadDeRutasYNuncaBaja() {
        PieroGGrafoVial g = rejilla();
        List<EberthMPoi> pois = pois();
        EberthMDemandaCiudad d = EberthMDemandaCiudad.generar(g, pois, null, EberthMTipoDia.LABORAL);
        List<trafiqueishon.ruta.LuisYPuntoImportante> puntos = new ArrayList<>();
        int i = 0;
        for (EberthMPoi p : pois) {
            puntos.add(new trafiqueishon.ruta.LuisYPuntoImportante("p" + i++, p.nombre(), p.lon(), p.lat(),
                    trafiqueishon.ruta.LuisYPuntoImportante.TipoPunto.EDUCACION));
        }
        var plan = pequeno(new EberthMGeneradorRutas(g, puntos, null, new java.util.Random(1)))
                .planificar(d, List.of(), new EberthMGeneradorRutas.Opciones(1, 4, 0, 10, 5), 6);
        assertEquals(plan.rutas().size() + 1, plan.cobertura().length);
        assertEquals(0, plan.cobertura()[0], 1e-9);
        for (int k = 1; k < plan.cobertura().length; k++) {
            assertTrue(plan.cobertura()[k] >= plan.cobertura()[k - 1] - 1e-12);
        }
        assertEquals(EberthMCoberturaDemanda.evaluar(d, plan.rutas()).porcentaje(),
                plan.coberturaMaxima(), 1e-9);
    }

    @Test
    void laEscalaSeAdaptaAlTamanoDeLaCiudad() {
        var pequena = new EberthMParametrosCiudad(30_000, 50, "07:00-09:00", 0,
                EberthMParametrosCiudad.TasaAccidentes.BAJA);
        var enorme = new EberthMParametrosCiudad(10_000_000, 70, "07:00-09:00", 0,
                EberthMParametrosCiudad.TasaAccidentes.BAJA);
        assertEquals(EberthMParametrosCiudad.ESCALA_POBLACION_SIMULADA, pequena.escalaPoblacionSimulada(), 1e-9);
        assertTrue(enorme.escalaPoblacionSimulada() > 100);
        assertTrue(enorme.usuariosTransporteDiarios() / enorme.escalaPoblacionSimulada()
                <= EberthMParametrosCiudad.USUARIOS_SIMULADOS_DIA_OBJETIVO + 1);
    }
}
