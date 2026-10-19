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

    public static final int CAPACIDAD_PARADA_POR_DEFECTO = 150;
    public static final int CAPACIDAD_PARADA_MAXIMA = 10_000;

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
            default -> new EberthMParametrosCiudad(200_000, 60,
                    "07:00-09:00, 17:00-19:00", 20, TasaAccidentes.MEDIA);
        };
    }

    public EberthMParametrosCiudad copia() {
        EberthMParametrosCiudad c = new EberthMParametrosCiudad(poblacionTotal,
                porcentajeTransportePublico, horasPunta, probabilidadLluvia, tasaAccidentes,
                porcentajeEstudiantes, porcentajeTrabajadores, porcentajeJubilados,
                capacidadMaximaParada);
        c.horasPuntaFinSemana = horasPuntaFinSemana;
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
