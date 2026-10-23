package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;
import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.geo.PieroGProyector;
import trafiqueishon.geo.PieroGVia;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.ruta.LuisYTrayecto;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EberthMRecalculadorRutasTest {

    private static EberthMServicioMicro servicio(String id, int frecuencia, int punta, int unidades) {
        PieroGVia via = new PieroGVia();
        via.id = "v";
        via.name = "Calle";
        via.highway = "residential";
        via.coords = new ArrayList<>(List.of(new double[]{-70.0, -15.0}, new double[]{-69.999, -15.0}));
        List<PieroGVia> vias = List.of(via);
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(vias);
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(vias, proyector);
        LuisYTrayecto trayecto = new LuisYTrayecto("t", "Ruta", "Empresa",
                List.of(new LuisYPuntoImportante("p", "P", -70.0, -15.0)),
                new ArrayList<>(grafo.getEdges()), 0, 0, "PARTIDA");
        return new EberthMServicioMicro(id, "Ruta " + id, trayecto, trayecto, frecuencia, unidades, 100,
                "#2E9E44", EberthMTipoRetorno.INVERSO, punta);
    }

    private static EberthMPosicionVehiculo micro(String idRuta, int pasajeros) {
        return new EberthMPosicionVehiculo(0, 0, EberthMPosicionVehiculo.TipoVehiculo.MINIBUS,
                pasajeros, 30, idRuta, "n", "#000000");
    }

    @Test
    void laRutaVaciaEspaciaSusSalidasYQuitaUnidades() {
        var r = EberthMRecalculadorRutas.ajustar(List.of(servicio("a", 5, 3, 10)),
                List.of(micro("a", 1), micro("a", 2)), 500);
        assertEquals(15, r.rutas().getFirst().frecuenciaMinutos());
        assertEquals(5, r.rutas().getFirst().unidadesDisponibles());
        assertEquals(1, r.ajustes().size());
        assertFalse(r.ajustes().getFirst().aumenta());
    }

    @Test
    void laRutaSaturadaAcercaSusSalidasYAnadeUnidades() {
        var r = EberthMRecalculadorRutas.ajustar(List.of(servicio("a", 10, 6, 4)),
                List.of(micro("a", 28), micro("a", 30)), 500);
        assertEquals(5, r.rutas().getFirst().frecuenciaMinutos());
        assertEquals(6, r.rutas().getFirst().unidadesDisponibles());
        assertTrue(r.ajustes().getFirst().aumenta());
    }

    @Test
    void laOcupacionNormalOLaFaltaDeDatosNoCambianNada() {
        var normal = servicio("a", 10, 6, 4);
        var sinDatos = servicio("b", 10, 6, 4);
        var r = EberthMRecalculadorRutas.ajustar(List.of(normal, sinDatos),
                List.of(micro("a", 15)), 500);
        assertSame(normal, r.rutas().get(0));
        assertSame(sinDatos, r.rutas().get(1));
        assertTrue(r.ajustes().isEmpty());
    }

    @Test
    void alAnadirUnidadesNoSeSuperaLaFlotaMaxima() {
        var r = EberthMRecalculadorRutas.ajustar(List.of(servicio("a", 10, 6, 4)),
                List.of(micro("a", 30)), 5);
        assertEquals(5, r.rutas().getFirst().unidadesDisponibles());
    }
}
