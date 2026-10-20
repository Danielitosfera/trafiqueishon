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
            assertEquals(r.pasajerosEsperando() > 150 ? 5 : 10, r.frecuenciaMinutos());
            assertEquals(EberthMGeneradorRutas.unidadesNecesarias(r.pasajerosEsperando(), r.frecuenciaMinutos()),
                    r.unidadesDisponibles());
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
    void unidadesSiguenLaFormulaDeLaDemanda() {
        assertEquals(2, EberthMGeneradorRutas.unidadesNecesarias(180, 10)); // 10 / 6 = 1,66
        assertEquals(2, EberthMGeneradorRutas.unidadesNecesarias(180, 5));  // 10 / 12 = 0,83 → mínimo 2
        assertEquals(20, EberthMGeneradorRutas.unidadesNecesarias(50_000, 5));
        assertEquals(5, EberthMGeneradorRutas.unidadesNecesarias(540, 10)); // 30 / 6
    }

    @Test
    void frecuenciaDependeDelUmbralDeDemanda() {
        assertEquals(5, EberthMGeneradorRutas.frecuenciaPorDemanda(151));
        assertEquals(10, EberthMGeneradorRutas.frecuenciaPorDemanda(150));
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
        var juliaca = new EberthMParametrosCiudad(280_000, 65, "07:00-09:00",
                20, EberthMParametrosCiudad.TasaAccidentes.MEDIA);
        assertEquals(36, EberthMGeneradorRutas.unidadesSugeridas(juliaca));
        juliaca.setPorcentajeTransportePublico(30);
        assertEquals(17, EberthMGeneradorRutas.unidadesSugeridas(juliaca));
        assertEquals(EberthMGeneradorRutas.MIN_UNIDADES, EberthMGeneradorRutas.unidadesSugeridas(null));
    }
}
