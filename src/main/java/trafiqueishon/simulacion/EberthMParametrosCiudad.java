package trafiqueishon.simulacion;

import trafiqueishon.geo.PieroGCiudadMapa;

import java.util.ArrayList;
import java.util.List;

/**
 * Parámetros demográficos y ambientales de una ciudad. Alimentan el generador orgánico de
 * eventos: horas punta, lluvia y accidentalidad.
 */
public final class EberthMParametrosCiudad {

    public enum TasaAccidentes {
        ALTA("Alta"), MEDIA("Media"), BAJA("Baja");

        private final String etiqueta;

        TasaAccidentes(String etiqueta) {
            this.etiqueta = etiqueta;
        }

        @Override
        public String toString() {
            return etiqueta;
        }
    }

    private long poblacionTotal;
    private double porcentajeTransportePublico;
    private String horasPunta;
    private double probabilidadLluvia;
    private TasaAccidentes tasaAccidentes;
    private double porcentajeEstudiantes = 25;
    private double porcentajeTrabajadores = 50;
    private double porcentajeJubilados = 25;
    private int capacidadMaximaParada = CAPACIDAD_PARADA_POR_DEFECTO;
    private String horasPuntaFinSemana = "11:00-14:00, 18:00-21:00";
    private double porcentajePoblacionEnPunta = PORCENTAJE_POBLACION_PUNTA_POR_DEFECTO;

    public static final int CAPACIDAD_PARADA_POR_DEFECTO = 150;
    /** % de la población total que viaja dentro de las horas punta (tope: todos los usuarios del transporte). */
    public static final double PORCENTAJE_POBLACION_PUNTA_POR_DEFECTO = 30;
    /** Ni siquiera con el máximo de gente en punta se vacían del todo las horas valle. */
    private static final double FRACCION_MAXIMA_EN_PUNTA = 0.95;
    private static final int MINUTOS_DIA = 24 * 60;
    public static final int CAPACIDAD_PARADA_MAXIMA = 10_000;

    /** Población por defecto de una ciudad sin parámetros propios (mapa importado nuevo). */
    public static final long POBLACION_POR_DEFECTO = 500_000;
    public static final double TRANSPORTE_POR_DEFECTO = 50;
    /**
     * Fracción de los viajes diarios en transporte público que cae en la hora de mayor demanda.
     * Orden de magnitud habitual en planes de movilidad urbana (10-15 %); es una estimación,
     * no un dato medido de la ciudad: conviene calibrarla con aforos.
     */
    public static final double FRACCION_HORA_PICO = EberthMGeneradorDemanda.PICO_MANANA / 2;
    /**
     * Cuántos habitantes que usan transporte público representa cada pasajero simulado. Lo
     * comparten la generación de demanda en las paradas y el dimensionado de flota, para que
     * la flota calculada atienda la misma gente que aparece en pantalla.
     */
    public static final double ESCALA_POBLACION_SIMULADA = 10;
    public static final long POBLACION_FRECUENCIA_ALTA = 1_000_000;
    public static final long POBLACION_FRECUENCIA_MEDIA = 500_000;

    public EberthMParametrosCiudad(long poblacionTotal, double porcentajeTransportePublico,
                                   String horasPunta, double probabilidadLluvia,
                                   TasaAccidentes tasaAccidentes) {
        setPoblacionTotal(poblacionTotal);
        setPorcentajeTransportePublico(porcentajeTransportePublico);
        setHorasPunta(horasPunta);
        setProbabilidadLluvia(probabilidadLluvia);
        setTasaAccidentes(tasaAccidentes);
    }

    public EberthMParametrosCiudad(long poblacionTotal, double porcentajeTransportePublico,
                                   String horasPunta, double probabilidadLluvia,
                                   TasaAccidentes tasaAccidentes, double estudiantes,
                                   double trabajadores, double jubilados, int capacidadMaximaParada) {
        this(poblacionTotal, porcentajeTransportePublico, horasPunta, probabilidadLluvia,
                tasaAccidentes);
        setDemografia(estudiantes, trabajadores, jubilados);
        setCapacidadMaximaParada(capacidadMaximaParada);
    }

    /** Valores realistas aproximados por ciudad; cualquier otra ciudad usa un perfil intermedio. */
    public static EberthMParametrosCiudad porDefecto(PieroGCiudadMapa ciudad) {
        return switch (ciudad.id()) {
            case "juliaca" -> new EberthMParametrosCiudad(280_000, 65,
                    "07:00-09:00, 12:30-14:00, 17:30-19:30", 35, TasaAccidentes.ALTA,
                    30, 45, 25, CAPACIDAD_PARADA_POR_DEFECTO);
            case "puno" -> new EberthMParametrosCiudad(150_000, 55,
                    "07:00-09:00, 17:30-19:30", 40, TasaAccidentes.MEDIA,
                    28, 47, 25, CAPACIDAD_PARADA_POR_DEFECTO);
            case "lima" -> new EberthMParametrosCiudad(10_000_000, 70,
                    "06:30-09:30, 17:30-20:30", 5, TasaAccidentes.ALTA,
                    22, 58, 20, CAPACIDAD_PARADA_POR_DEFECTO);
            default -> new EberthMParametrosCiudad(POBLACION_POR_DEFECTO, TRANSPORTE_POR_DEFECTO,
                    "07:00-09:00, 17:00-19:00", 20, TasaAccidentes.MEDIA);
        };
    }

    /** Usuarios diarios de transporte público: población × % de uso. */
    public double usuariosTransporteDiarios() {
        return poblacionTotal * porcentajeTransportePublico / 100.0;
    }

    /** Personas que viajan en transporte público durante la hora de mayor demanda (día laboral). */
    public double demandaPicoHora() {
        return usuariosTransporteDiarios() * fraccionHorariaPico(EberthMTipoDia.LABORAL);
    }

    /**
     * Perfil de demanda del día: fracción de los usuarios diarios que viaja en cada minuto (suma 1).
     * La gente que viaja en hora punta ({@link #getPorcentajePoblacionEnPunta()} de la población,
     * como mucho todos los usuarios) se reparte por igual dentro de las horas punta configuradas;
     * el resto, fuera de ellas, con poca actividad de madrugada (22:00-05:00).
     */
    public double[] perfilPorMinuto(EberthMTipoDia dia) {
        boolean[] punta = new boolean[MINUTOS_DIA];
        int minutosPunta = 0;
        String rangos = dia.esLaboral() ? horasPunta : horasPuntaFinSemana;
        for (int[] r : interpretarHorasPunta(rangos)) {
            for (int m = 0; m < MINUTOS_DIA; m++) {
                boolean dentro = r[0] <= r[1] ? m >= r[0] && m < r[1] : m >= r[0] || m < r[1];
                if (dentro && !punta[m]) {
                    punta[m] = true;
                    minutosPunta++;
                }
            }
        }
        double usuarios = usuariosTransporteDiarios();
        double enPunta = minutosPunta == 0 || usuarios <= 0 ? 0
                : Math.min(FRACCION_MAXIMA_EN_PUNTA, poblacionTotal * porcentajePoblacionEnPunta / 100.0 / usuarios);
        double pesoValle = 0;
        for (int m = 0; m < MINUTOS_DIA; m++) {
            if (!punta[m]) pesoValle += m < 5 * 60 || m >= 22 * 60 ? 0.1 : 1.0;
        }
        double[] perfil = new double[MINUTOS_DIA];
        for (int m = 0; m < MINUTOS_DIA; m++) {
            perfil[m] = punta[m] ? enPunta / minutosPunta
                    : (m < 5 * 60 || m >= 22 * 60 ? 0.1 : 1.0) * (1 - enPunta) / Math.max(1e-9, pesoValle);
        }
        return perfil;
    }

    /** Fracción de los usuarios diarios que viaja en la hora indicada. */
    public double fraccionHoraria(int hora, EberthMTipoDia dia) {
        return fraccionesHorarias(dia)[Math.floorMod(hora, 24)];
    }

    /** Fracción de los usuarios diarios en cada una de las 24 horas. */
    public double[] fraccionesHorarias(EberthMTipoDia dia) {
        double[] perfil = perfilPorMinuto(dia);
        double[] horas = new double[24];
        for (int m = 0; m < MINUTOS_DIA; m++) horas[m / 60] += perfil[m];
        return horas;
    }

    /** Fracción de los usuarios diarios que viaja en la hora más cargada. */
    public double fraccionHorariaPico(EberthMTipoDia dia) {
        double max = 0;
        for (double f : fraccionesHorarias(dia)) max = Math.max(max, f);
        return max;
    }

    public double getPorcentajePoblacionEnPunta() {
        return porcentajePoblacionEnPunta;
    }

    public void setPorcentajePoblacionEnPunta(double porcentaje) {
        this.porcentajePoblacionEnPunta = porcentajeValido(porcentaje, "El porcentaje de población en hora punta");
    }

    /** Pasajeros simulados en la hora de mayor demanda (personas ÷ {@link #escalaPoblacionSimulada()}). */
    public double demandaPicoSimuladaHora() {
        return demandaPicoHora() / escalaPoblacionSimulada();
    }

    /** Usuarios simulados por día a los que apunta la escala para que cualquier ciudad sea manejable. */
    public static final double USUARIOS_SIMULADOS_DIA_OBJETIVO = 50_000;

    /**
     * Habitantes que representa cada pasajero simulado. Es {@value #ESCALA_POBLACION_SIMULADA}
     * como mínimo y crece en ciudades muy grandes para que el total simulado por día no pase de
     * unos {@value #USUARIOS_SIMULADOS_DIA_OBJETIVO}: así un mapa nuevo, pequeño o enorme, rinde igual.
     */
    public double escalaPoblacionSimulada() {
        return Math.max(ESCALA_POBLACION_SIMULADA,
                Math.ceil(usuariosTransporteDiarios() / USUARIOS_SIMULADOS_DIA_OBJETIVO));
    }

    /** Frecuencia de salida base según el tamaño de la ciudad: 5 min (&gt; 1 M), 8 min (&gt; 500 mil), 10 min. */
    public int frecuenciaBaseMinutos() {
        if (poblacionTotal > POBLACION_FRECUENCIA_ALTA) return 5;
        if (poblacionTotal > POBLACION_FRECUENCIA_MEDIA) return 8;
        return 10;
    }

    public EberthMParametrosCiudad copia() {
        EberthMParametrosCiudad c = new EberthMParametrosCiudad(poblacionTotal,
                porcentajeTransportePublico, horasPunta, probabilidadLluvia, tasaAccidentes,
                porcentajeEstudiantes, porcentajeTrabajadores, porcentajeJubilados,
                capacidadMaximaParada);
        c.horasPuntaFinSemana = horasPuntaFinSemana;
        c.porcentajePoblacionEnPunta = porcentajePoblacionEnPunta;
        return c;
    }

    /**
     * Interpreta rangos {@code "HH:mm-HH:mm"} separados por comas o punto y coma. Un rango que
     * cruza la medianoche (p. ej. {@code 22:00-01:00}) es válido.
     *
     * @return pares {@code {inicio, fin}} en minutos del día
     * @throws IllegalArgumentException si algún rango no tiene el formato esperado
     */
    public static List<int[]> interpretarHorasPunta(String texto) {
        List<int[]> rangos = new ArrayList<>();
        if (texto == null || texto.isBlank()) return rangos;
        for (String parte : texto.split("[,;]")) {
            String rango = parte.trim();
            if (rango.isEmpty()) continue;
            String[] extremos = rango.split("-");
            if (extremos.length != 2) {
                throw new IllegalArgumentException("Rango no válido «" + rango + "»; usa HH:mm-HH:mm");
            }
            rangos.add(new int[]{minutos(extremos[0]), minutos(extremos[1])});
        }
        return rangos;
    }

    private static int minutos(String hora) {
        String[] partes = hora.trim().split(":");
        try {
            if (partes.length != 2) throw new NumberFormatException();
            int h = Integer.parseInt(partes[0].trim());
            int m = Integer.parseInt(partes[1].trim());
            if (h < 0 || h > 24 || m < 0 || m > 59 || (h == 24 && m != 0)) {
                throw new NumberFormatException();
            }
            return h * 60 + m;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Hora no válida «" + hora.trim() + "»; usa HH:mm");
        }
    }

    /** {@code true} si el minuto del día cae en alguna de las horas punta configuradas. */
    public boolean esHoraPunta(int minutoDelDia) {
        return esHoraPuntaDe(horasPunta, minutoDelDia);
    }

    private static boolean esHoraPuntaDe(String rangos, int minutoDelDia) {
        int minuto = Math.floorMod(minutoDelDia, 24 * 60);
        for (int[] r : interpretarHorasPunta(rangos)) {
            boolean dentro = r[0] <= r[1]
                    ? minuto >= r[0] && minuto < r[1]
                    : minuto >= r[0] || minuto < r[1];
            if (dentro) return true;
        }
        return false;
    }

    /** Hora punta según el tipo de día: sábados, domingos y feriados usan su propia lista. */
    public boolean esHoraPunta(int minutoDelDia, EberthMTipoDia dia) {
        return dia.esLaboral() ? esHoraPunta(minutoDelDia) : esHoraPuntaDe(horasPuntaFinSemana, minutoDelDia);
    }

    public String getHorasPuntaFinSemana() {
        return horasPuntaFinSemana;
    }

    public void setHorasPuntaFinSemana(String horas) {
        interpretarHorasPunta(horas);
        this.horasPuntaFinSemana = horas == null ? "" : horas.trim();
    }

    public long getPoblacionTotal() {
        return poblacionTotal;
    }

    public void setPoblacionTotal(long poblacionTotal) {
        if (poblacionTotal < 0) throw new IllegalArgumentException("La población no puede ser negativa");
        this.poblacionTotal = poblacionTotal;
    }

    public double getPorcentajeTransportePublico() {
        return porcentajeTransportePublico;
    }

    public void setPorcentajeTransportePublico(double porcentaje) {
        this.porcentajeTransportePublico = porcentajeValido(porcentaje, "El uso de transporte público");
    }

    public String getHorasPunta() {
        return horasPunta;
    }

    public void setHorasPunta(String horasPunta) {
        interpretarHorasPunta(horasPunta); // valida el formato antes de aceptarlo
        this.horasPunta = horasPunta == null ? "" : horasPunta.trim();
    }

    public double getProbabilidadLluvia() {
        return probabilidadLluvia;
    }

    public void setProbabilidadLluvia(double porcentaje) {
        this.probabilidadLluvia = porcentajeValido(porcentaje, "La probabilidad de lluvia");
    }

    public TasaAccidentes getTasaAccidentes() {
        return tasaAccidentes;
    }

    public void setTasaAccidentes(TasaAccidentes tasaAccidentes) {
        this.tasaAccidentes = tasaAccidentes == null ? TasaAccidentes.MEDIA : tasaAccidentes;
    }

    public double getPorcentajeEstudiantes() {
        return porcentajeEstudiantes;
    }

    public double getPorcentajeTrabajadores() {
        return porcentajeTrabajadores;
    }

    public double getPorcentajeJubilados() {
        return porcentajeJubilados;
    }

    /** Los tres porcentajes se validan juntos: cada uno entre 0 y 100 y suma 100. */
    public void setDemografia(double estudiantes, double trabajadores, double jubilados) {
        porcentajeValido(estudiantes, "El porcentaje de estudiantes");
        porcentajeValido(trabajadores, "El porcentaje de trabajadores");
        porcentajeValido(jubilados, "El porcentaje de jubilados");
        if (Math.abs(estudiantes + trabajadores + jubilados - 100) > 0.01) {
            throw new IllegalArgumentException("Estudiantes + trabajadores + jubilados debe sumar 100 %");
        }
        this.porcentajeEstudiantes = estudiantes;
        this.porcentajeTrabajadores = trabajadores;
        this.porcentajeJubilados = jubilados;
    }

    public int getCapacidadMaximaParada() {
        return capacidadMaximaParada;
    }

    public void setCapacidadMaximaParada(int capacidad) {
        if (capacidad < 1 || capacidad > CAPACIDAD_PARADA_MAXIMA) {
            throw new IllegalArgumentException("La capacidad por parada debe estar entre 1 y "
                    + CAPACIDAD_PARADA_MAXIMA);
        }
        this.capacidadMaximaParada = capacidad;
    }

    private static double porcentajeValido(double valor, String nombre) {
        if (Double.isNaN(valor) || valor < 0 || valor > 100) {
            throw new IllegalArgumentException(nombre + " debe estar entre 0 y 100");
        }
        return valor;
    }
}
