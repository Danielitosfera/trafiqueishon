package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;
import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.geo.PieroGProyector;
import trafiqueishon.geo.PieroGVia;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.ruta.LuisYTrayecto;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EberthMDespachadorTest {

    private static final double MINUTO = 1.0;

    @Test
    void lasDosPrimerasUnidadesSalenJuntasUnaDeCadaTerminal() {
        EberthMDespachador despachador = new EberthMDespachador(servicio(10, 4, 100));

        EberthMDespachador.Salida primera = despachador.siguienteSalida(0, MINUTO).orElseThrow();
        EberthMDespachador.Salida segunda = despachador.siguienteSalida(0, MINUTO).orElseThrow();

        assertFalse(primera.desdeTerminalFinal());
        assertTrue(segunda.desdeTerminalFinal());
        assertEquals(0, primera.numeroUnidad());
        assertEquals(1, segunda.numeroUnidad());
        assertTrue(despachador.siguienteSalida(0, MINUTO).isEmpty());
    }

    @Test
    void respetaLaFrecuenciaEntreSalidasYAlternaTerminal() {
        EberthMDespachador despachador = new EberthMDespachador(servicio(10, 4, 100));
        despachador.siguienteSalida(0, MINUTO);
        despachador.siguienteSalida(0, MINUTO);

        assertTrue(despachador.siguienteSalida(599, MINUTO).isEmpty());
        Optional<EberthMDespachador.Salida> tercera = despachador.siguienteSalida(600, MINUTO);
        Optional<EberthMDespachador.Salida> cuarta = despachador.siguienteSalida(1200, MINUTO);

        assertFalse(tercera.orElseThrow().desdeTerminalFinal());
        assertTrue(cuarta.orElseThrow().desdeTerminalFinal());
        assertTrue(despachador.terminado());
        assertEquals(4, despachador.unidadesDespachadas());
    }

    @Test
    void noSaleNingunaUnidadMasDeLasDisponibles() {
        EberthMDespachador despachador = new EberthMDespachador(servicio(1, 2, 0));

        int salidas = 0;
        while (despachador.siguienteSalida(100_000, MINUTO).isPresent()) salidas++;

        assertEquals(2, salidas);
        assertTrue(despachador.terminado());
        assertEquals(0, despachador.minutosHastaProximoDespacho(100_000));
    }

    @Test
    void unaSolaUnidadAvanzaElProximoDespachoDeInmediato() {
        EberthMDespachador despachador = new EberthMDespachador(servicio(5, 1, 10));

        despachador.siguienteSalida(0, MINUTO);

        assertEquals(300.0, despachador.proximoDespacho(), 1e-9);
        assertTrue(despachador.terminado());
    }

    @Test
    void asignaPasajerosSegunLaDemandaDelServicio() {
        EberthMDespachador despachador = new EberthMDespachador(servicio(10, 4, 100));

        EberthMDespachador.Salida primera = despachador.siguienteSalida(0, MINUTO).orElseThrow();

        assertEquals(18, primera.pasajeros());
    }

    @Test
    void finalizarDetieneLasSalidasPendientes() {
        EberthMDespachador despachador = new EberthMDespachador(servicio(10, 4, 100));

        despachador.finalizar();

        assertTrue(despachador.terminado());
        assertTrue(despachador.siguienteSalida(1_000_000, MINUTO).isEmpty());
    }

    @Test
    void calculaLosMinutosHastaElProximoDespacho() {
        EberthMDespachador despachador = new EberthMDespachador(servicio(10, 4, 100));
        despachador.siguienteSalida(0, MINUTO);
        despachador.siguienteSalida(0, MINUTO);

        assertEquals(10, despachador.minutosHastaProximoDespacho(0));
        assertEquals(5, despachador.minutosHastaProximoDespacho(300));
    }

    // ------------------------------------------------------------------ ayudas

    @Test
    void sinRetornoTodasLasUnidadesSalenDelTerminalDePartida() {
        EberthMServicioMicro base = servicio(10, 4, 100);
        EberthMServicioMicro sinRetorno = new EberthMServicioMicro(base.id(), base.nombre(),
                base.trayecto(), base.trayectoVuelta(), 10, 4, 100, base.color(),
                EberthMTipoRetorno.SIN_RETORNO);
        EberthMDespachador despachador = new EberthMDespachador(sinRetorno);

        for (int segundos : new int[]{0, 0, 600, 1200}) {
            assertFalse(despachador.siguienteSalida(segundos, MINUTO).orElseThrow().desdeTerminalFinal());
        }
        assertFalse(sinRetorno.tieneRetorno());
    }

    @Test
    void laEscalaMultiplicaLaDemandaPorSalida() {
        EberthMServicioMicro base = servicio(10, 4, 100);
        assertEquals(25, new EberthMDemandaPasajeros(100, 4).demandaPorSalida());
        EberthMDespachador x10 = new EberthMDespachador(base, 10);
        // 1.000 pasajeros entre 4 unidades: más de 18 por salida, así que abordan 18 y el resto queda.
        assertEquals(18, x10.siguienteSalida(0, MINUTO).orElseThrow().pasajeros());
        assertThrows(IllegalArgumentException.class, () -> new EberthMDespachador(base, 0));
    }

    private static EberthMServicioMicro servicio(int frecuenciaMinutos, int unidades, int pasajeros) {
        PieroGVia via = new PieroGVia();
        via.id = "v";
        via.name = "Calle";
        via.highway = "residential";
        via.coords = new ArrayList<>(List.of(new double[]{-70.0, -15.0},
                new double[]{-69.999, -15.0}));
        List<PieroGVia> vias = List.of(via);
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(vias);
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(vias, proyector);
        LuisYTrayecto trayecto = new LuisYTrayecto("t", "Ruta", "Empresa",
                List.of(new LuisYPuntoImportante("p", "P", -70.0, -15.0)),
                new ArrayList<>(grafo.getEdges()), 0, 0, "PARTIDA");
        return new EberthMServicioMicro("s1", "Servicio", trayecto, trayecto, frecuenciaMinutos,
                unidades, pasajeros, "#2E9E44");
    }

    @Test
    void enHoraPuntaUsaLaFrecuenciaPuntaYFueraDeEllaLaNormal() {
        EberthMServicioMicro base = servicio(10, 6, 100);
        EberthMServicioMicro conPunta = new EberthMServicioMicro(base.id(), base.nombre(), base.trayecto(),
                base.trayectoVuelta(), 10, 6, 100, base.color(), base.retorno(), 2);
        EberthMDespachador despachador = new EberthMDespachador(conPunta);
        despachador.siguienteSalida(0, MINUTO, true);
        despachador.siguienteSalida(0, MINUTO, true);
        assertTrue(despachador.siguienteSalida(119, MINUTO, true).isEmpty());
        assertTrue(despachador.siguienteSalida(120, MINUTO, true).isPresent());
        assertEquals(240, despachador.proximoDespacho(), 1e-9);
        assertTrue(despachador.siguienteSalida(240, MINUTO, false).isPresent());
        assertEquals(840, despachador.proximoDespacho(), 1e-9);
    }

    @Test
    void sinFrecuenciaPuntaPropiaSeDerivaDeLaNormal() {
        assertEquals(7, servicio(10, 4, 100).frecuenciaPuntaMinutos());
        assertEquals(1, servicio(1, 4, 100).frecuenciaPuntaMinutos());
    }
}
