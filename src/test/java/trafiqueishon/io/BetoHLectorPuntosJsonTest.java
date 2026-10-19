package trafiqueishon.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trafiqueishon.ruta.LuisYPuntoImportante;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BetoHLectorPuntosJsonTest {
    @TempDir
    Path directorioTemporal;

    @Test
    void guardaYRecuperaUbicacionesConCoordenadasYTipo() throws Exception {
        BetoHLectorPuntosJson lector = new BetoHLectorPuntosJson();
        List<LuisYPuntoImportante> originales = List.of(new LuisYPuntoImportante(
                "terminal-1", "Terminal", -70.12345, -15.54321,
                LuisYPuntoImportante.TipoPunto.TERMINAL));
        Path archivo = directorioTemporal.resolve("puntos.json");

        lector.guardar(originales, archivo);

        assertEquals(originales, lector.leer(archivo));
    }
}
