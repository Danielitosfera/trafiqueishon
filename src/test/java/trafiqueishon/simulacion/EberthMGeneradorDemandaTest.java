package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class EberthMGeneradorDemandaTest {

    @Test
    void lasFraccionesHorariasSumanUno() {
        double suma = 0;
        for (int h = 0; h < 24; h++) suma += EberthMGeneradorDemanda.fraccionHoraria(h);
        assertEquals(1.0, suma, 1e-9);
    }

    @Test
    void juliacaTieneAlrededorDe30milEnHoraPunta() {
        double usuarios = 280_000 * 0.65;
        double punta = EberthMGeneradorDemanda.pasajerosHora(usuarios, 7);
        assertTrue(punta > 28_000 && punta < 34_000, "punta = " + punta);
    }

    @Test
    void alas7LasEscuelasSonElDestinoDel60PorCiento() {
        assertEquals(0.60, EberthMGeneradorDemanda.pesoCategoria(EberthMCategoriaPoi.EDUCACION, 7), 0.0);
    }

    @Test
    void elDestinoFavoreceAlPoiMasPopularDeLaHora() {
        EberthMPoi escuela = new EberthMPoi("Escuela", 0, 0, EberthMCategoriaPoi.EDUCACION);
        EberthMPoi parque = new EberthMPoi("Parque", 0, 0, EberthMCategoriaPoi.TURISMO);
        Random azar = new Random(1);
        int escuelas = 0;
        for (int i = 0; i < 2000; i++) {
            if (EberthMGeneradorDemanda.sortearDestino(List.of(escuela, parque), 7, azar) == escuela) escuelas++;
        }
        assertTrue(escuelas > 1700, "escuelas = " + escuelas);
    }
}
