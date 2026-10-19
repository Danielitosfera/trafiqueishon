package trafiqueishon.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGNodo;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.ruta.LuisYTrayecto;
import trafiqueishon.simulacion.EberthMServicioMicro;
import trafiqueishon.simulacion.EberthMTipoRetorno;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BetoHRepositorioServiciosMicroTest {
    @TempDir
    Path temporal;

    @Test
    void guardaYRecuperaLaConfiguracionDeLaRuta() throws Exception {
        PieroGNodo a = new PieroGNodo(0, 0, 0, -70, -15);
        PieroGNodo b = new PieroGNodo(1, 1, 0, -69.999, -15);
        PieroGEdge edge = new PieroGEdge(0, a, b, 100, "residential",
                "Avenida", 30, false, 1, null);
        List<LuisYPuntoImportante> puntos = List.of(
                new LuisYPuntoImportante("a", "Paradero A", -70, -15),
                new LuisYPuntoImportante("b", "Paradero B", -69.999, -15));
        LuisYTrayecto ida = new LuisYTrayecto("r1", "Micro Centro", "Empresa", puntos,
                List.of(edge), 100, edge.costoBase, "SERVICIO");
        LuisYTrayecto vuelta = new LuisYTrayecto("r1-vuelta", "Micro Centro vuelta", "Empresa",
                List.of(puntos.get(1), puntos.get(0)), List.of(edge), 100,
                edge.costoBase, "SERVICIO_VUELTA");
        EberthMServicioMicro servicio = new EberthMServicioMicro("r1", "Micro Centro", ida, vuelta,
                12, EberthMServicioMicro.MAX_UNIDADES, 80, "#39FF14");
        BetoHRepositorioServiciosMicro repositorio = new BetoHRepositorioServiciosMicro(
                temporal.resolve("rutas.json"));

        repositorio.guardar(List.of(servicio));

        BetoHRepositorioServiciosMicro.RutaGuardada cargada = repositorio.cargar().getFirst();
        assertEquals(servicio.id(), cargada.id());
        assertEquals(servicio.nombre(), cargada.nombre());
        assertEquals(servicio.trayecto().puntos(), cargada.paraderos());
        assertEquals(12, cargada.frecuenciaMinutos());
        assertEquals(EberthMServicioMicro.MAX_UNIDADES, cargada.unidadesDisponibles());
        assertEquals(80, cargada.pasajerosEsperando());
        assertEquals(servicio.color(), cargada.color());
    }

    @Test
    void guardaElTipoDeRetornoYLosParaderosDeVueltaManual() throws Exception {
        PieroGNodo a = new PieroGNodo(0, 0, 0, -70, -15);
        PieroGNodo b = new PieroGNodo(1, 1, 0, -69.999, -15);
        PieroGEdge edge = new PieroGEdge(0, a, b, 100, "residential",
                "Avenida", 30, false, 1, null);
        var pa = new LuisYPuntoImportante("a", "Paradero A", -70, -15);
        var pb = new LuisYPuntoImportante("b", "Paradero B", -69.999, -15);
        var pc = new LuisYPuntoImportante("c", "Paradero C", -69.998, -15);
        LuisYTrayecto ida = new LuisYTrayecto("r1", "M", "E", List.of(pa, pb),
                List.of(edge), 100, edge.costoBase, "SERVICIO");
        LuisYTrayecto vuelta = new LuisYTrayecto("r1-vuelta", "M v", "E", List.of(pc, pa),
                List.of(edge), 100, edge.costoBase, "SERVICIO_VUELTA");
        EberthMServicioMicro manual = new EberthMServicioMicro("r1", "M", ida, vuelta,
                12, 3, 80, "#39FF14", EberthMTipoRetorno.MANUAL);
        BetoHRepositorioServiciosMicro repositorio = new BetoHRepositorioServiciosMicro(
                temporal.resolve("rutas2.json"));

        repositorio.guardar(List.of(manual));

        var cargada = repositorio.cargar().getFirst();
        assertEquals("MANUAL", cargada.retorno());
        assertEquals(List.of(pc, pa), cargada.paraderosVuelta());
    }
}
