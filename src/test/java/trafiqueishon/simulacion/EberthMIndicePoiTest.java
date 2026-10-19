package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;
import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGNodo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EberthMIndicePoiTest {

    // 0.001° de longitud en lat -15 ≈ 107 m.
    private static PieroGEdge tramo(int id, double lon0, double lon1) {
        return new PieroGEdge(id, new PieroGNodo(id, 0, 0, lon0, -15),
                new PieroGNodo(id + 100, 1, 0, lon1, -15), 1000, "residential",
                "Avenida", 30, false, 1, null);
    }

    @Test
    void soloDevuelveLosPoisACuatrocientosMetrosDeLaRutaConSuPrimerTramo() {
        List<PieroGEdge> ruta = List.of(tramo(0, -70.00, -69.99), tramo(1, -69.99, -69.98));
        EberthMPoi cerca = new EberthMPoi("Mercado", -69.985, -14.9985);   // ~165 m de la vía
        EberthMPoi lejos = new EberthMPoi("Hospital", -69.985, -14.99);    // ~1.1 km
        EberthMPoi enPrimerTramo = new EberthMPoi("Colegio", -69.995, -15.0);
        EberthMIndicePoi indice = new EberthMIndicePoi(List.of(cerca, lejos, enPrimerTramo));

        var candidatos = indice.candidatosEnRuta(ruta);

        assertEquals(2, candidatos.size());
        assertEquals(enPrimerTramo, candidatos.get(0).poi());
        assertEquals(0, candidatos.get(0).indiceEdge());
        assertEquals(cerca, candidatos.get(1).poi());
        assertEquals(1, candidatos.get(1).indiceEdge());
    }

    @Test
    void elIndiceVacioNoDevuelveCandidatos() {
        assertTrue(EberthMIndicePoi.VACIO.candidatosEnRuta(List.of(tramo(0, -70, -69.99))).isEmpty());
    }

    @Test
    void distanciaAlSegmentoUsaElPuntoMasCercano() {
        double d = EberthMIndicePoi.distanciaPuntoSegmento(-69.995, -14.9995, -70, -15, -69.99, -15);
        assertEquals(55.7, d, 1.0);
        // Más allá del extremo: distancia al extremo, no a la recta.
        assertTrue(EberthMIndicePoi.distanciaPuntoSegmento(-69.98, -15, -70, -15, -69.99, -15) > 1000);
    }

    @Test
    void proyectarEnSegmentoDevuelveLaFraccionDelPuntoMasCercano() {
        // POI ~300 m al norte del punto medio del segmento: el pie de la perpendicular es t = 0.5.
        double[] p = EberthMIndicePoi.proyectarEnSegmento(-69.995, -14.9973, -70, -15, -69.99, -15);
        assertEquals(0.5, p[1], 1e-6);
        assertEquals(300, p[0], 2.0);
    }
}
