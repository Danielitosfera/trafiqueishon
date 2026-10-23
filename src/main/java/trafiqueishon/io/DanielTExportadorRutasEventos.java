package trafiqueishon.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.ruta.LuisYTrayecto;
import trafiqueishon.simulacion.EberthMEventoTrafico;
import trafiqueishon.simulacion.EberthMMotorSimulacion.Instantanea;
import trafiqueishon.simulacion.EberthMServicioMicro;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Exporta a JSON las rutas (ida y vuelta), los eventos activos con sus coordenadas y un resumen
 * de las estadísticas del momento.
 */
public final class DanielTExportadorRutasEventos {

    private final ObjectMapper mapper = new ObjectMapper();

    public void exportar(String ciudad, List<EberthMServicioMicro> rutas, List<EberthMEventoTrafico> eventos,
                         Instantanea foto, Path destino) throws IOException {
        Files.writeString(destino, mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(construir(ciudad, rutas, eventos, foto)));
    }

    ObjectNode construir(String ciudad, List<EberthMServicioMicro> rutas, List<EberthMEventoTrafico> eventos,
                         Instantanea foto) {
        ObjectNode raiz = mapper.createObjectNode();
        raiz.put("ciudad", ciudad == null ? "" : ciudad);
        raiz.put("generado", java.time.LocalDateTime.now().toString());

        ArrayNode jsonRutas = raiz.putArray("rutas");
        for (EberthMServicioMicro ruta : rutas) {
            ObjectNode r = jsonRutas.addObject();
            r.put("nombre", ruta.nombre());
            r.put("color", ruta.color());
            r.put("frecuencia_min", ruta.frecuenciaMinutos());
            r.put("frecuencia_punta_min", ruta.frecuenciaPuntaMinutos());
            r.put("unidades", ruta.unidadesDisponibles());
            r.set("ida", sentido(ruta.trayecto()));
            if (ruta.tieneRetorno()) r.set("vuelta", sentido(ruta.trayectoVuelta()));
        }

        ArrayNode jsonEventos = raiz.putArray("eventos");
        for (EberthMEventoTrafico e : eventos) {
            ObjectNode n = jsonEventos.addObject();
            n.put("nombre", e.nombre());
            n.put("tipo", e.tipo().name());
            n.put("lon", e.lon());
            n.put("lat", e.lat());
            if (e.radioMetros() > 0) n.put("radio_m", e.radioMetros());
        }

        ObjectNode est = raiz.putObject("estadisticas");
        est.put("simulacion_activa", foto.haySimulacion());
        int minutos = foto.horaMinutos();
        est.put("hora_simulada", String.format("%02d:%02d", minutos / 60, minutos % 60));
        est.put("micros_en_circulacion", foto.vehiculos().size());
        est.put("pasajeros_transportados", foto.pasajerosTransportados());
        est.put("pasajeros_generados", foto.pasajerosGenerados());
        est.put("pasajeros_abandonaron", foto.pasajerosAbandonaron());
        int atendidos = foto.pasajerosTransportados() + foto.pasajerosAbandonaron();
        if (atendidos > 0) {
            est.put("satisfaccion_pct", Math.round(1000.0 * foto.pasajerosTransportados() / atendidos) / 10.0);
        }
        est.put("ocupacion_promedio_pct", Math.round(foto.ocupacionPromedio() * 1000) / 10.0);
        est.put("espera_promedio_min", Math.round(foto.esperaPromedioMin() * 10) / 10.0);
        est.put("micros_con_incidencia", foto.vehiculosConIncidencia());
        est.put("zonas_sin_cobertura", foto.zonasSinCobertura());
        return raiz;
    }

    private ObjectNode sentido(LuisYTrayecto trayecto) {
        ObjectNode n = mapper.createObjectNode();
        n.put("distancia_km", Math.round(trayecto.distanciaMetros()) / 1000.0);
        ArrayNode coords = n.putArray("coordenadas");
        for (PieroGEdge e : trayecto.edges()) coords.addArray().add(e.origen().lon).add(e.origen().lat);
        if (!trayecto.edges().isEmpty()) {
            var ultimo = trayecto.edges().getLast().destino();
            coords.addArray().add(ultimo.lon).add(ultimo.lat);
        }
        return n;
    }
}
