package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;
import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.geo.PieroGProyector;
import trafiqueishon.geo.PieroGVia;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.ruta.LuisYPuntoImportante.TipoPunto;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EberthMGeneradorRutasTest {

    private static final double PASO = 0.0009; // ≈ 100 m
    private static final int N = 80;           // cuadrícula de ≈ 8 km

    @Test
    void generaRutasDistintasConParadasIntermediasYColoresUnicos() {
        PieroGGrafoVial red = cuadricula();
        List<LuisYPuntoImportante> pois = pois();

        List<EberthMServicioMicro> rutas =
                new EberthMGeneradorRutas(red, pois, ciudad(), new Random(7)).generar(List.of(), 4);

        assertTrue(rutas.size() >= 3, "rutas generadas: " + rutas.size());
        Set<String> colores = new HashSet<>();
        Set<String> nombres = new HashSet<>();
        for (EberthMServicioMicro r : rutas) {
            assertTrue(colores.add(r.color()));
            assertTrue(nombres.add(r.nombre()));
            assertTrue(r.nombre().startsWith("Ruta Auto "));
            List<LuisYPuntoImportante> paradas = r.trayecto().puntos();
            assertTrue(paradas.size() >= 3 && paradas.size() <= 5, "paradas: " + paradas.size());
            double km = Math.hypot(
                    (paradas.getFirst().lon() - paradas.getLast().lon()) * 111_320 * Math.cos(Math.toRadians(-15)),
                    (paradas.getFirst().lat() - paradas.getLast().lat()) * 111_320);
            assertTrue(km >= 3_000, "origen-destino: " + km);
            assertEquals(EberthMTipoRetorno.INVERSO, r.retorno());
            assertEquals(EberthMGeneradorRutas.frecuenciaPorCiudad(ciudad()), r.frecuenciaMinutos());
            assertTrue(r.unidadesDisponibles() >= EberthMGeneradorRutas.MIN_UNIDADES);
        }
    }

    @Test
    void numeraDespuesDeLasRutasAutoExistentes() {
        PieroGGrafoVial red = cuadricula();
        List<EberthMServicioMicro> primeras =
                new EberthMGeneradorRutas(red, pois(), ciudad(), new Random(1)).generar(List.of(), 1);
        assertEquals("Ruta Auto 1", primeras.getFirst().nombre());

        List<EberthMServicioMicro> mas =
                new EberthMGeneradorRutas(red, pois(), ciudad(), new Random(2)).generar(primeras, 1);
        assertEquals("Ruta Auto 2", mas.getFirst().nombre());
    }

    @Test
    void sinPuntosSuficientesNoGeneraNada() {
        assertTrue(new EberthMGeneradorRutas(cuadricula(), List.of(), ciudad(), new Random(1))
                .generar(List.of(), 3).isEmpty());
    }

    @Test
    void frecuenciaDependeDeLaPoblacionDeLaCiudad() {
        assertEquals(5, EberthMGeneradorRutas.frecuenciaPorCiudad(ciudadConPoblacion(1_500_000)));
        assertEquals(8, EberthMGeneradorRutas.frecuenciaPorCiudad(ciudadConPoblacion(600_000)));
        assertEquals(10, EberthMGeneradorRutas.frecuenciaPorCiudad(ciudadConPoblacion(280_000)));
        assertEquals(10, EberthMGeneradorRutas.frecuenciaPorCiudad(null));
        // Los límites son estrictos: justo 1 M y justo 500 mil caen en el tramo inferior.
        assertEquals(8, EberthMGeneradorRutas.frecuenciaPorCiudad(ciudadConPoblacion(1_000_000)));
        assertEquals(10, EberthMGeneradorRutas.frecuenciaPorCiudad(ciudadConPoblacion(500_000)));
    }

    @Test
    void demandaPicoEsPoblacionPorTransportePorQuinceCentesimosEntreLaEscalaSimulada() {
        var juliaca = ciudadConPoblacion(280_000); // 65 % de uso
        double esperado = 280_000 * 0.65 * juliaca.fraccionHorariaPico(EberthMTipoDia.LABORAL);
        assertEquals(esperado, juliaca.demandaPicoHora(), 1e-6);
        assertEquals(esperado / EberthMParametrosCiudad.ESCALA_POBLACION_SIMULADA,
                juliaca.demandaPicoSimuladaHora(), 1e-6);
    }

    @Test
    void unidadesPorRutaSigueLaDemandaDeLaCiudadYLaVueltaCompleta() {
        var ciudad = ciudadConPoblacion(280_000);
        // Sin tope de frecuencia (punta 1 min): la hora punta de 07:00-09:00 concentra el 30 % de la
        // población (46 % de los usuarios), unos 4 200 pax/h simulados repartidos entre 5 rutas.
        assertEquals(47, EberthMGeneradorRutas.unidadesPorRuta(ciudad, 5, 60, 1));
        // La misma ruta con vuelta de 30 min necesita la mitad.
        assertEquals(24, EberthMGeneradorRutas.unidadesPorRuta(ciudad, 5, 30, 1));
        // Más rutas reparten la demanda.
        assertTrue(EberthMGeneradorRutas.unidadesPorRuta(ciudad, 10, 60, 1)
                < EberthMGeneradorRutas.unidadesPorRuta(ciudad, 5, 60, 1));
        // Una ciudad mayor necesita más unidades.
        assertTrue(EberthMGeneradorRutas.unidadesPorRuta(ciudadConPoblacion(2_000_000), 5, 60, 1)
                > EberthMGeneradorRutas.unidadesPorRuta(ciudad, 5, 60, 1));
    }

    @Test
    void unidadesPorRutaTieneMinimoDosYTopeSegunLaFrecuenciaDePunta() {
        // Ciudad diminuta: la fórmula da menos de 2 → se usa 2.
        assertEquals(2, EberthMGeneradorRutas.unidadesPorRuta(ciudadConPoblacion(5_000), 5, 60, 7));
        // Con demanda alta no caben más unidades que vuelta ÷ frecuencia de punta (60 / 7 → 9).
        assertEquals(9, EberthMGeneradorRutas.unidadesPorRuta(ciudadConPoblacion(280_000), 5, 60, 7));
        assertEquals(2, EberthMGeneradorRutas.unidadesPorRuta(null, 5, 60, 7));
    }

    @Test
    void laVueltaIncluyeIdaVueltaParadasYEsperaEnTerminales() {
        // 20 min de ida → 40 min; 5 paradas × 2 × 15 s = 2,5 min; 2 esperas de 10 min.
        assertEquals(40 + 2.5 + 20, EberthMGeneradorRutas.vueltaCompletaMinutos(20 * 60, 10, 5), 1e-9);
    }

    private static EberthMParametrosCiudad ciudadConPoblacion(long poblacion) {
        var c = new EberthMParametrosCiudad(poblacion, 65, "07:00-09:00", 20,
                EberthMParametrosCiudad.TasaAccidentes.MEDIA);
        return c;
    }

    @Test
    void laDemandaSubeConLosPoisYLaPoblacion() {
        double juliaca = EberthMGeneradorRutas.multiplicadorPoblacion(280_000);
        double lima = EberthMGeneradorRutas.multiplicadorPoblacion(10_000_000);
        assertTrue(lima > juliaca && juliaca > 1);
        assertTrue(EberthMGeneradorRutas.demandaBase(9, juliaca) > EberthMGeneradorRutas.demandaBase(6, juliaca));
        assertTrue(EberthMGeneradorRutas.demandaBase(9, lima) > EberthMGeneradorRutas.demandaBase(9, juliaca));
    }

    private static EberthMParametrosCiudad ciudad() {
        return EberthMParametrosCiudad.porDefecto(trafiqueishon.geo.PieroGCiudadMapa.JULIACA);
    }

    private static List<LuisYPuntoImportante> pois() {
        TipoPunto[] tipos = {TipoPunto.SALUD, TipoPunto.MERCADO, TipoPunto.EDUCACION, TipoPunto.COMERCIO};
        List<LuisYPuntoImportante> lista = new ArrayList<>();
        int id = 0;
        for (int x = 2; x < N; x += 11) {
            for (int y = 2; y < N; y += 11) {
                lista.add(new LuisYPuntoImportante("p" + id, "POI " + id,
                        -70.0 + x * PASO, -15.0 + y * PASO, tipos[id % tipos.length]));
                id++;
            }
        }
        return lista;
    }

    private static PieroGGrafoVial cuadricula() {
        List<PieroGVia> vias = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            PieroGVia h = via("h" + i), v = via("v" + i);
            for (int j = 0; j < N; j++) {
                h.coords.add(new double[]{-70.0 + j * PASO, -15.0 + i * PASO});
                v.coords.add(new double[]{-70.0 + i * PASO, -15.0 + j * PASO});
            }
            vias.add(h);
            vias.add(v);
        }
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(vias);
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(vias, proyector);
        return grafo;
    }

    private static PieroGVia via(String nombre) {
        PieroGVia via = new PieroGVia();
        via.id = nombre;
        via.name = nombre;
        via.highway = "residential";
        via.coords = new ArrayList<>();
        return via;
    }

    @Test
    void flotaSugeridaSigueALaPoblacionYAlPorcentajeDeTransporte() {
        var ciudad = ciudadConPoblacion(280_000);
        int base = EberthMGeneradorRutas.unidadesSugeridas(ciudad);
        ciudad.setPorcentajeTransportePublico(30);
        assertTrue(EberthMGeneradorRutas.unidadesSugeridas(ciudad) <= base);
        assertEquals(EberthMGeneradorRutas.MIN_UNIDADES, EberthMGeneradorRutas.unidadesSugeridas(null));
    }

    @Test
    void unaCiudadSinParametrosPropiosUsaLosValoresPorDefecto() {
        var otra = EberthMParametrosCiudad.porDefecto(new trafiqueishon.geo.PieroGCiudadMapa(
                "arequipa", "Arequipa", false));
        assertEquals(EberthMParametrosCiudad.POBLACION_POR_DEFECTO, otra.getPoblacionTotal());
        assertEquals(EberthMParametrosCiudad.TRANSPORTE_POR_DEFECTO, otra.getPorcentajeTransportePublico(), 0);
    }

    @Test
    void lasRutasNuevasPrefierenPoisCercanosAZonasSinCobertura() {
        var puntos = pois();
        var objetivo = puntos.getFirst();
        int sesgadas = extremosEn(puntos, objetivo.id(),
                new double[]{objetivo.lon(), objetivo.lat(), 500});
        int neutras = extremosEn(puntos, objetivo.id(), null);
        assertTrue(sesgadas > neutras, "con zona: " + sesgadas + ", sin zona: " + neutras);
    }

    /** Cuántas de 40 rutas de una sola generación tienen el POI dado como extremo. */
    private static int extremosEn(List<LuisYPuntoImportante> puntos, String idPoi, double[] zonas) {
        PieroGGrafoVial red = cuadricula();
        int cuenta = 0;
        for (int i = 0; i < 40; i++) {
            var gen = new EberthMGeneradorRutas(red, puntos, ciudad(), new Random(100 + i));
            gen.priorizarZonasSinCobertura(zonas);
            for (var r : gen.generar(List.of(), 1)) {
                var ps = r.trayecto().puntos();
                if (ps.getFirst().id().equals(idPoi) || ps.getLast().id().equals(idPoi)) cuenta++;
            }
        }
        return cuenta;
    }
}
