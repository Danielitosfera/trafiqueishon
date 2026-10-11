package trafiqueishon.simulacion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RutaMicroStoreTest {
    @Test
    void routeConfigurationRetainsLogoAndStopsInJson() throws Exception {
        RutaMicro ruta = new RutaMicro("M1", List.of("origen", "parada", "destino"),
                8, 12, "/logos/m1.png");
        ObjectMapper mapper = new ObjectMapper();
        RutaMicro restaurada = mapper.readValue(mapper.writeValueAsBytes(ruta), RutaMicro.class);
        assertEquals(ruta, restaurada);
    }
}
