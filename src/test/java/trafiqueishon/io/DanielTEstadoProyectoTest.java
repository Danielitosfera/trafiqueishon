package trafiqueishon.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trafiqueishon.geo.PieroGCiudadMapa;
import trafiqueishon.io.BetoHRepositorioServiciosMicro.RutaGuardada;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.simulacion.EberthMParametrosCiudad;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DanielTEstadoProyectoTest {

    private static DanielTEstadoProyecto ejemplo() {
        var params = EberthMParametrosCiudad.porDefecto(PieroGCiudadMapa.PUNO);
        params.setCapacidadMaximaParada(90);
        var a = new LuisYPuntoImportante("a", "Terminal", -70.0, -15.0, LuisYPuntoImportante.TipoPunto.TERMINAL);
        var b = new LuisYPuntoImportante("b", "Colegio", -69.99, -15.0, LuisYPuntoImportante.TipoPunto.EDUCACION);
        var ruta = new RutaGuardada("r1", "Línea 1", List.of(a, b), 10, 3, 20, "#39FF14");
        return new DanielTEstadoProyecto(1, "puno", "Puno", false,
                BetoHRepositorioConfigCiudades.ParametrosGuardados.desde(params),
                List.of(ruta), List.of(a, b), 7 * 60 + 30, "SABADO", true);
    }

    @Test
    void guardaYRecuperaTodoElEstado(@TempDir Path dir) throws Exception {
        Path archivo = dir.resolve("sub/mi.traf");
        ejemplo().guardar(archivo);

        var leido = DanielTEstadoProyecto.cargar(archivo);

        assertEquals("puno", leido.ciudadId());
        assertEquals(90, leido.parametros().aParametros().getCapacidadMaximaParada());
        assertEquals(1, leido.rutas().size());
        assertEquals("Línea 1", leido.rutas().getFirst().nombre());
        assertEquals(2, leido.rutas().getFirst().paraderos().size());
        assertEquals(LuisYPuntoImportante.TipoPunto.EDUCACION, leido.puntos().get(1).tipo());
        assertEquals(450, leido.horaMinutos());
        assertEquals("SABADO", leido.tipoDia());
        assertTrue(leido.eventosOrganicos());
    }

    @Test
    void rechazaArchivosCorruptosOAjenos(@TempDir Path dir) throws Exception {
        Path basura = dir.resolve("a.traf");
        Files.writeString(basura, "esto no es json");
        assertThrows(IOException.class, () -> DanielTEstadoProyecto.cargar(basura));

        Path ajeno = dir.resolve("b.json");
        Files.writeString(ajeno, "{\"hola\":1}");
        assertThrows(IOException.class, () -> DanielTEstadoProyecto.cargar(ajeno));
    }
}
