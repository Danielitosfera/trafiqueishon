package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PersistenciaEscenarioTest {
    @TempDir
    Path directorio;

    @Test
    void referencesAndSimulationPreferencesSurviveReload() throws Exception {
        PersistenciaEscenario store = new PersistenciaEscenario(directorio);
        List<MarcadorReferencia> referencias = List.of(
                new MarcadorReferencia("Terminal nuevo", -70.12, -15.49));
        var preferencias = new PersistenciaEscenario.Preferencias(
                800, 12_000, 3, Map.of("PARTICULAR", 85.0), true);
        store.guardarReferencias(referencias);
        store.guardarPreferencias(preferencias);
        Path mapa = directorio.resolve("otra-ciudad.geojson");
        java.nio.file.Files.writeString(mapa, "{}");
        store.guardarGeoJsonSeleccionado(mapa);

        PersistenciaEscenario recargado = new PersistenciaEscenario(directorio);
        assertEquals(referencias, recargado.cargarReferencias());
        assertEquals(preferencias, recargado.cargarPreferencias());
        assertEquals(mapa.toAbsolutePath(), recargado.cargarGeoJsonSeleccionado());
    }
}
