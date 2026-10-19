package trafiqueishon.simulacion;

import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.io.BetoHRepositorioServiciosMicro;
import trafiqueishon.ruta.LuisYCalculadorTrayecto;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.ruta.LuisYTrayecto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Lógica de los servicios de micro y trayectos que antes vivía en la ventana principal:
 * recalcular rutas con los costos actuales del grafo, fusionar resultados, elegir colores
 * y calcular trayectos de partida/retorno. No depende de JavaFX.
 */
public final class EberthMGestorServicios {

    private static final Logger LOG = Logger.getLogger(EberthMGestorServicios.class.getName());

    private static final List<String> COLORES_MICROS = List.of(
            "#39FF14", "#FFEA00", "#FF3D00", "#E500FF", "#00F5FF",
            "#FF1493", "#B6FF00", "#FF00A8", "#00FF85", "#FF6EC7");

    private EberthMGestorServicios() {}

    /** Recalcula ida y vuelta de cada servicio con los costos actuales del grafo. */
    public static List<EberthMServicioMicro> recalcular(PieroGGrafoVial red,
                                                        List<EberthMServicioMicro> servicios) {
        LuisYCalculadorTrayecto calc = new LuisYCalculadorTrayecto(red);
        List<EberthMServicioMicro> actualizadas = new ArrayList<>();
        for (EberthMServicioMicro ruta : servicios) {
            try {
                LuisYTrayecto ida = calc.calcular(ruta.id(), ruta.nombre(),
                        ruta.trayecto().empresa(), "SERVICIO", ruta.trayecto().puntos());
                LuisYTrayecto vuelta = calc.calcular(ruta.id() + "-vuelta",
                        ruta.nombre() + " · vuelta", ruta.trayectoVuelta().empresa(),
                        "SERVICIO_VUELTA", ruta.trayectoVuelta().puntos());
                actualizadas.add(new EberthMServicioMicro(ruta.id(), ruta.nombre(), ida, vuelta,
                        ruta.frecuenciaMinutos(), ruta.unidadesDisponibles(),
                        ruta.pasajerosEsperando(), ruta.color(), ruta.retorno()));
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "No se pudo recalcular " + ruta.nombre()
                        + "; se conserva su trazado", ex);
                actualizadas.add(ruta);
            }
        }
        return actualizadas;
    }

    /**
     * Sustituye en {@code actuales} (modificándola) solo las rutas que nadie editó mientras se
     * recalculaban, es decir, las que siguen siendo iguales a las de {@code originales}.
     *
     * @return las rutas nuevas que se pusieron, vacío si no hubo cambios
     */
    public static List<EberthMServicioMicro> fusionarRecalculadas(List<EberthMServicioMicro> actuales,
            List<EberthMServicioMicro> originales, List<EberthMServicioMicro> recalculadas) {
        Map<String, EberthMServicioMicro> porId = recalculadas.stream()
                .collect(Collectors.toMap(EberthMServicioMicro::id, Function.identity(), (a, b) -> b));
        List<EberthMServicioMicro> aplicadas = new ArrayList<>();
        for (int i = 0; i < actuales.size(); i++) {
            EberthMServicioMicro actual = actuales.get(i);
            EberthMServicioMicro nueva = porId.get(actual.id());
            if (nueva != null && originales.contains(actual)) {
                actuales.set(i, nueva);
                aplicadas.add(nueva);
            }
        }
        return aplicadas;
    }

    public static int indiceDe(List<EberthMServicioMicro> servicios, String id) {
        for (int i = 0; i < servicios.size(); i++) {
            if (servicios.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    /** Primer color de la paleta no usado; si se agotan, recorre los tonos HSB. */
    public static String siguienteColor(List<EberthMServicioMicro> servicios) {
        Set<String> usados = servicios.stream()
                .map(r -> r.color().toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        for (String candidato : COLORES_MICROS) {
            if (!usados.contains(candidato)) return candidato;
        }
        for (int tono = 0; tono < 360; tono++) {
            String candidato = hexDeTono(tono);
            if (!usados.contains(candidato)) return candidato;
        }
        return "#FFFFFF";
    }

    /** Color de saturación y brillo máximos para un tono 0-359. */
    private static String hexDeTono(int tono) {
        double h = tono / 60.0;
        double x = 1 - Math.abs(h % 2 - 1);
        double[] rgb = switch ((int) h) {
            case 0 -> new double[]{1, x, 0};
            case 1 -> new double[]{x, 1, 0};
            case 2 -> new double[]{0, 1, x};
            case 3 -> new double[]{0, x, 1};
            case 4 -> new double[]{x, 0, 1};
            default -> new double[]{1, 0, x};
        };
        return String.format("#%02X%02X%02X", Math.round(rgb[0] * 255),
                Math.round(rgb[1] * 255), Math.round(rgb[2] * 255));
    }

    /**
     * Trayecto que recorre todos los puntos. {@code "RETORNO"} es el inverso del orden óptimo
     * de partida; cualquier otro sentido calcula el orden óptimo directamente.
     */
    public static LuisYTrayecto calcularTrayecto(PieroGGrafoVial red, String sentido,
                                                 List<LuisYPuntoImportante> puntos) {
        LuisYCalculadorTrayecto calculador = new LuisYCalculadorTrayecto(red);
        String id = sentido.toLowerCase(Locale.ROOT) + "_" + System.currentTimeMillis();
        if ("RETORNO".equals(sentido)) {
            LuisYTrayecto partidaOptima = calculador.calcularOrdenOptimo(id + "_base",
                    "Trayecto PARTIDA", "Trafiqueishon", "PARTIDA", puntos);
            List<LuisYPuntoImportante> inverso = new ArrayList<>(partidaOptima.puntos());
            Collections.reverse(inverso);
            return calculador.calcular(id, "Trayecto RETORNO", "Trafiqueishon", sentido, inverso);
        }
        return calculador.calcularOrdenOptimo(id, "Trayecto PARTIDA", "Trafiqueishon", sentido, puntos);
    }

    /** {@code true} si otra ruta (distinta de {@code id}) ya usa ese color. */
    public static boolean colorEnUso(List<EberthMServicioMicro> servicios, String id, String colorHex) {
        return servicios.stream().anyMatch(r ->
                !r.id().equals(id) && r.color().equalsIgnoreCase(colorHex));
    }

    /** {@code true} si al guardar {@code servicio} la flota total supera el máximo del simulador. */
    public static boolean flotaExcedida(List<EberthMServicioMicro> servicios,
                                        EberthMServicioMicro servicio) {
        long total = servicios.stream().filter(r -> !r.id().equals(servicio.id()))
                .mapToLong(EberthMServicioMicro::unidadesDisponibles).sum()
                + servicio.unidadesDisponibles();
        return total > EberthMSimuladorBuses.MAX_UNIDADES;
    }

    /**
     * Calcula ida y vuelta de una micro nueva o editada. La vuelta es la ida invertida salvo en
     * {@link EberthMTipoRetorno#MANUAL}, que usa {@code vueltaManual}. Sin retorno se calcula igual
     * la vuelta inversa, porque la unidad debe poder regresar al terminal. Puede tardar segundos
     * en mapas grandes.
     */
    public static EberthMServicioMicro construirServicio(PieroGGrafoVial red, String id, String nombre,
            List<LuisYPuntoImportante> paraderos, boolean optimizarOrden, EberthMTipoRetorno retorno,
            List<LuisYPuntoImportante> vueltaManual, int frecuenciaMin, int unidades,
            int pasajeros, String colorHex) {
        LuisYCalculadorTrayecto calc = new LuisYCalculadorTrayecto(red);
        LuisYTrayecto ida = optimizarOrden
                ? calc.calcularOrdenOptimo(id, nombre, "Trafiqueishon", "SERVICIO", paraderos)
                : calc.calcular(id, nombre, "Trafiqueishon", "SERVICIO", paraderos);
        boolean manual = retorno == EberthMTipoRetorno.MANUAL;
        List<LuisYPuntoImportante> puntosVuelta = new ArrayList<>(manual ? vueltaManual : ida.puntos());
        if (!manual) Collections.reverse(puntosVuelta);
        LuisYTrayecto vuelta = calc.calcular(id + "-vuelta", nombre + " · vuelta",
                "Trafiqueishon", "SERVICIO_VUELTA", puntosVuelta);
        return new EberthMServicioMicro(id, nombre, ida, vuelta, frecuenciaMin, unidades,
                pasajeros, colorHex, retorno);
    }

    /** Reconstruye las micros guardadas (la vuelta es la ida invertida); omite las que fallen. */
    public static List<EberthMServicioMicro> restaurar(PieroGGrafoVial red,
            List<BetoHRepositorioServiciosMicro.RutaGuardada> guardadas) {
        List<EberthMServicioMicro> restauradas = new ArrayList<>();
        LuisYCalculadorTrayecto calc = new LuisYCalculadorTrayecto(red);
        for (BetoHRepositorioServiciosMicro.RutaGuardada guardada : guardadas) {
            try {
                LuisYTrayecto ida = calc.calcular(guardada.id(), guardada.nombre(),
                        "Trafiqueishon", "SERVICIO", guardada.paraderos());
                EberthMTipoRetorno retorno = EberthMTipoRetorno.desdeTexto(guardada.retorno());
                boolean manual = retorno == EberthMTipoRetorno.MANUAL
                        && guardada.paraderosVuelta().size() >= 2;
                if (retorno == EberthMTipoRetorno.MANUAL && !manual) retorno = EberthMTipoRetorno.INVERSO;
                List<LuisYPuntoImportante> paraderosVuelta = new ArrayList<>(
                        manual ? guardada.paraderosVuelta() : guardada.paraderos());
                if (!manual) Collections.reverse(paraderosVuelta);
                LuisYTrayecto vuelta = calc.calcular(guardada.id() + "-vuelta",
                        guardada.nombre() + " · vuelta", "Trafiqueishon",
                        "SERVICIO_VUELTA", paraderosVuelta);
                restauradas.add(new EberthMServicioMicro(guardada.id(), guardada.nombre(),
                        ida, vuelta, guardada.frecuenciaMinutos(),
                        guardada.unidadesDisponibles(), guardada.pasajerosEsperando(),
                        guardada.color(), retorno));
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "Se omitió ruta guardada: " + guardada.nombre(), t);
            }
        }
        return restauradas;
    }
}
