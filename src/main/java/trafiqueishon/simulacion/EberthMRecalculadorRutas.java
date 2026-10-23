package trafiqueishon.simulacion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ajusta la frecuencia y la flota de cada ruta según la ocupación que muestran sus micros en
 * circulación: bajo {@link #OCUPACION_BAJA} se espacian las salidas y se quitan unidades; sobre
 * {@link #OCUPACION_ALTA} se acercan las salidas y se añaden unidades. No toca trazados ni dibuja.
 */
public final class EberthMRecalculadorRutas {
    private EberthMRecalculadorRutas() {}

    public static final double OCUPACION_BAJA = 0.20;
    public static final double OCUPACION_ALTA = 0.80;
    /** Ruta de baja ocupación: la frecuencia se multiplica por esto (5 min → 15 min). */
    static final int FACTOR_ESPACIAR = 3;
    /** Ruta saturada: la frecuencia se divide entre esto (10 min → 5 min). */
    static final int FACTOR_ACERCAR = 2;
    /** Frecuencia máxima admitida por {@link EberthMServicioMicro}. */
    static final int FRECUENCIA_MAXIMA = 180;

    public record Ajuste(String idRuta, String nombre, double ocupacion, int frecuenciaAntes,
                         int frecuenciaDespues, int unidadesAntes, int unidadesDespues) {
        public boolean aumenta() { return frecuenciaDespues < frecuenciaAntes; }
    }

    /** @param rutas la lista completa, en el mismo orden, con los ajustes ya aplicados */
    public record Resultado(List<EberthMServicioMicro> rutas, List<Ajuste> ajustes) {}

    /** Ocupación (pasajeros ÷ capacidad) de cada ruta con micros en circulación; las demás no figuran. */
    public static Map<String, Double> ocupacionPorRuta(List<EberthMPosicionVehiculo> vehiculos) {
        Map<String, long[]> sumas = new HashMap<>();
        for (EberthMPosicionVehiculo v : vehiculos) {
            if (v.idRuta() == null || v.idRuta().isBlank() || v.capacidad() <= 0) continue;
            long[] s = sumas.computeIfAbsent(v.idRuta(), k -> new long[2]);
            s[0] += v.pasajeros();
            s[1] += v.capacidad();
        }
        Map<String, Double> resultado = new HashMap<>();
        sumas.forEach((id, s) -> resultado.put(id, (double) s[0] / s[1]));
        return resultado;
    }

    /**
     * @param flotaMaxima tope de unidades sumando todas las rutas; al añadir unidades no se supera
     */
    public static Resultado ajustar(List<EberthMServicioMicro> rutas,
                                    List<EberthMPosicionVehiculo> vehiculos, int flotaMaxima) {
        Map<String, Double> ocupacion = ocupacionPorRuta(vehiculos);
        long flota = rutas.stream().mapToLong(EberthMServicioMicro::unidadesDisponibles).sum();
        List<EberthMServicioMicro> nuevas = new ArrayList<>(rutas.size());
        List<Ajuste> ajustes = new ArrayList<>();
        for (EberthMServicioMicro ruta : rutas) {
            Double ocup = ocupacion.get(ruta.id());
            EberthMServicioMicro ajustada = ruta;
            if (ocup != null && ocup < OCUPACION_BAJA) {
                int freq = Math.min(FRECUENCIA_MAXIMA, ruta.frecuenciaMinutos() * FACTOR_ESPACIAR);
                int punta = Math.min(freq, ruta.frecuenciaPuntaMinutos() * FACTOR_ESPACIAR);
                int unidades = Math.max(1, (ruta.unidadesDisponibles() + 1) / 2);
                ajustada = con(ruta, freq, punta, unidades);
            } else if (ocup != null && ocup > OCUPACION_ALTA) {
                int freq = Math.max(1, (ruta.frecuenciaMinutos() + FACTOR_ACERCAR - 1) / FACTOR_ACERCAR);
                int punta = Math.max(1, (ruta.frecuenciaPuntaMinutos() + FACTOR_ACERCAR - 1) / FACTOR_ACERCAR);
                int deseadas = ruta.unidadesDisponibles() + Math.max(1, ruta.unidadesDisponibles() / 2);
                long libres = Math.max(0, flotaMaxima - flota);
                int unidades = (int) Math.min(deseadas, ruta.unidadesDisponibles() + libres);
                ajustada = con(ruta, freq, punta, unidades);
            }
            if (ajustada != ruta) {
                flota += ajustada.unidadesDisponibles() - ruta.unidadesDisponibles();
                if (ajustada.frecuenciaMinutos() != ruta.frecuenciaMinutos()
                        || ajustada.unidadesDisponibles() != ruta.unidadesDisponibles()) {
                    ajustes.add(new Ajuste(ruta.id(), ruta.nombre(), ocup, ruta.frecuenciaMinutos(),
                            ajustada.frecuenciaMinutos(), ruta.unidadesDisponibles(),
                            ajustada.unidadesDisponibles()));
                } else {
                    ajustada = ruta;
                }
            }
            nuevas.add(ajustada);
        }
        return new Resultado(nuevas, ajustes);
    }

    private static EberthMServicioMicro con(EberthMServicioMicro r, int frecuencia, int punta, int unidades) {
        return new EberthMServicioMicro(r.id(), r.nombre(), r.trayecto(), r.trayectoVuelta(), frecuencia,
                unidades, r.pasajerosEsperando(), r.color(), r.retorno(), punta);
    }
}
