package trafiqueishon.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trafiqueishon.geo.PieroGCiudadMapa;
import trafiqueishon.simulacion.EberthMParametrosCiudad;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BetoHRepositorioConfigCiudadesTest {

    @Test
    void guardaYRecuperaLosParametrosDeCadaCiudad(@TempDir Path dir) throws Exception {
        var repo = new BetoHRepositorioConfigCiudades(dir.resolve("sub/config_ciudades.json"));
        var juliaca = EberthMParametrosCiudad.porDefecto(PieroGCiudadMapa.JULIACA);
        juliaca.setPoblacionTotal(321_000);
        juliaca.setHorasPunta("06:00-08:00");
        juliaca.setTasaAccidentes(EberthMParametrosCiudad.TasaAccidentes.BAJA);
        juliaca.setDemografia(40, 40, 20);
        juliaca.setCapacidadMaximaParada(80);

        repo.guardar(Map.of("juliaca", juliaca, "lima", EberthMParametrosCiudad.porDefecto(PieroGCiudadMapa.LIMA)));
        var leidos = repo.cargar();

        assertEquals(2, leidos.size());
        assertEquals(321_000, leidos.get("juliaca").getPoblacionTotal());
        assertEquals(40, leidos.get("juliaca").getPorcentajeEstudiantes());
        assertEquals(80, leidos.get("juliaca").getCapacidadMaximaParada());
        assertEquals("06:00-08:00", leidos.get("juliaca").getHorasPunta());
        assertEquals(EberthMParametrosCiudad.TasaAccidentes.BAJA, leidos.get("juliaca").getTasaAccidentes());
    }

    @Test
    void sinArchivoDevuelveVacioYUnaCiudadCorruptaSeOmite(@TempDir Path dir) throws Exception {
        var archivo = dir.resolve("config_ciudades.json");
        var repo = new BetoHRepositorioConfigCiudades(archivo);
        assertTrue(repo.cargar().isEmpty());

        Files.writeString(archivo, """
                {"puno":{"poblacionTotal":150000,"porcentajeTransportePublico":55,
                         "horasPunta":"07:00-09:00","probabilidadLluvia":40,"tasaAccidentes":"MEDIA"},
                 "lima":{"poblacionTotal":-5,"porcentajeTransportePublico":70,
                         "horasPunta":"xx","probabilidadLluvia":5,"tasaAccidentes":"ALTA"}}
                """);
        var leidos = repo.cargar();
        assertEquals(1, leidos.size());
        assertTrue(leidos.containsKey("puno"));
        assertEquals(150, leidos.get("puno").getCapacidadMaximaParada()); // archivo antiguo: por defecto
    }
}
