package trafiqueishon.simulacion;

import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.geo.PieroGPathfinder;
import trafiqueishon.geo.PieroGRutaResult;
import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGNodo;
import trafiqueishon.ruta.LuisYPaletaColores;
import trafiqueishon.ruta.LuisYTrayecto;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class EberthMSimuladorBuses {
    public static final int MAX_UNIDADES = EberthMServicioMicro.MAX_UNIDADES;
    private static final Logger LOG = Logger.getLogger(EberthMSimuladorBuses.class.getName());
    private static final int MAX_DESVIOS_CACHE = 2_048;
    private static final double REINTENTO_SIN_RUTA_SEGUNDOS = 2.0;
    private static final int MAX_DESVIOS_POR_PASO = 16;
    private static final double DISTANCIA_FRENADO_SEMAFORO_METROS = 15.0;
    private static final double DISTANCIA_SEGURIDAD_METROS = 15.0;
    /** Cuántas vías por delante revisa cada vehículo para enterarse de un bloqueo a tiempo. */
    private static final int VIAS_DE_ANTICIPACION = 6;
    /** Radios (≈ 2 cuadras, luego más) del corredor en que se busca el desvío. */
    private static final double[] RADIOS_DESVIO_METROS = {200, 500};
    private final List<Vehiculo> vehiculos = new ArrayList<>();
    private final List<DespachoProgramado> despachos = new ArrayList<>();
    private PieroGGrafoVial grafo;
    private PieroGPathfinder pathfinder;
    private List<EberthMEventoTrafico> eventos = List.of();
    private List<EberthMEventoTrafico> gasolineras = List.of();
    private final Map<RutaDesvioKey, List<PieroGEdge>> desviosCache = new HashMap<>();
    private long versionRed;
    private double velocidadSimulacion = 1.0;
    private boolean ejecutando;
    private double segundosTranscurridos;
    private int horaInicioMinutos;
    private int ultimoMinutoFactorPunta = -1;
    private volatile boolean horaPuntaForzada;
    private boolean ultimaHoraPuntaForzada;
    private static final int MINUTO_HORA_PUNTA_FORZADA = 18 * 60;
    private static final double MINUTO_HORA_PUNTA_FORZADA_FACTOR = 2.0;
    private int pasajerosTransportados;
    public static final int MAX_ESCALA_DEMANDA = 100;

    /** Eventos que el generador orgánico creó o dejó caducar en un minuto simulado. */
    public record CambioEventosOrganicos(List<EberthMEventoTrafico> nuevos,
                                         List<EberthMEventoTrafico> expirados) {}

    private final EberthMGeneradorEventos generadorEventos =
            new EberthMGeneradorEventos(new java.util.Random());
    private volatile boolean eventosOrganicos;
    private EberthMTipoDia tipoDia = EberthMTipoDia.LABORAL;
    private EberthMParametrosCiudad parametrosCiudad;
    private java.util.function.Consumer<CambioEventosOrganicos> oyenteEventosOrganicos;
    private int ultimoMinutoOrganico;

    private final DemandaParadas demandaParadas = new DemandaParadas();
    private int escalaDemanda = 1;

    public void iniciar(LuisYTrayecto trayecto, int cantidad) {
        Objects.requireNonNull(trayecto, "El trayecto es obligatorio.");
        if (cantidad < 1 || cantidad > MAX_UNIDADES) {
            throw new IllegalArgumentException("La cantidad de unidades debe estar entre 1 y "
                    + MAX_UNIDADES + ".");
        }
        if (trayecto.edges().isEmpty()) {
            throw new IllegalArgumentException("El trayecto no contiene tramos.");
        }
        vehiculos.clear();
        despachos.clear();
        pasajerosTransportados = 0;
        for (int i = 0; i < cantidad; i++) {
            EberthMPosicionVehiculo.TipoVehiculo tipo = i % 3 == 0
                    ? EberthMPosicionVehiculo.TipoVehiculo.MINIBUS
                    : EberthMPosicionVehiculo.TipoVehiculo.BUS;
            vehiculos.add(new Vehiculo(trayecto.edges(), trayecto.edges(), tipo,
                    0, "", "Flota", LuisYPaletaColores.COLOR_RUTA_ACTIVA, 0));
        }
        ejecutando = true;
    }

    public void iniciar(LuisYTrayecto trayecto, int buses, int micros, int pasajeros) {
        Objects.requireNonNull(trayecto, "El trayecto es obligatorio.");
        long totalUnidades = (long) buses + micros;
        long capacidadFlota = (long) buses * 50 + (long) micros * 18;
        if (buses < 0 || micros < 0 || totalUnidades < 1
                || totalUnidades > MAX_UNIDADES) {
            throw new IllegalArgumentException("Selecciona hasta "
                    + MAX_UNIDADES + " unidades.");
        }
        if (pasajeros < 0 || pasajeros > capacidadFlota) {
            throw new IllegalArgumentException("Los pasajeros superan la capacidad de la flota.");
        }
        if (trayecto.edges().isEmpty()) {
            throw new IllegalArgumentException("El trayecto no contiene tramos.");
        }
        vehiculos.clear();
        despachos.clear();
        pasajerosTransportados = pasajeros;
        int pasajerosPendientes = pasajeros;
        int cantidad = (int) totalUnidades;
        for (int i = 0; i < cantidad; i++) {
            EberthMPosicionVehiculo.TipoVehiculo tipo = i < buses
                    ? EberthMPosicionVehiculo.TipoVehiculo.BUS : EberthMPosicionVehiculo.TipoVehiculo.MINIBUS;
            int asignados = Math.min(capacidad(tipo), pasajerosPendientes);
            pasajerosPendientes -= asignados;
            vehiculos.add(new Vehiculo(trayecto.edges(), trayecto.edges(), tipo, asignados,
                    "", "Flota", LuisYPaletaColores.COLOR_RUTA_ACTIVA, 0));
        }
        ejecutando = true;
    }

    public void iniciar(List<EberthMServicioMicro> servicios, int hora, int minuto) {
        Objects.requireNonNull(servicios, "La lista de servicios es obligatoria.");
        if (servicios.isEmpty()) throw new IllegalArgumentException("No hay rutas de micros activas.");
        long totalUnidades = servicios.stream().mapToLong(EberthMServicioMicro::unidadesDisponibles).sum();
        if (totalUnidades > MAX_UNIDADES) {
            throw new IllegalArgumentException("La flota total no puede superar "
                    + MAX_UNIDADES + " unidades.");
        }
        if (hora < 0 || hora > 23 || minuto < 0 || minuto > 59) {
            throw new IllegalArgumentException("La hora de inicio no es válida.");
        }
        vehiculos.clear();
        despachos.clear();
        segundosTranscurridos = 0;
        pasajerosTransportados = 0;
        horaInicioMinutos = hora * 60 + minuto;
        ultimoMinutoFactorPunta = -1;
        ultimoMinutoOrganico = 0;
        generadorEventos.reiniciar();
        demandaParadas.reiniciar();
        for (EberthMServicioMicro servicio : servicios) {
            DespachoProgramado despacho = new DespachoProgramado(servicio, escalaDemanda);
            despacho.paradasIda = nodosParadas(servicio.trayecto());
            despacho.paradasVuelta = paradasRetorno(servicio);
            demandaParadas.registrar(despacho.paradasIda);
            demandaParadas.registrar(despacho.paradasVuelta);
            despachos.add(despacho);
        }
        ejecutando = true;
        procesarDespachos();
    }

    public void detener() {
        ejecutando = false;
    }

    public void pausar() {
        ejecutando = false;
    }

    public void reanudar() {
        if (!vehiculos.isEmpty() || !despachos.isEmpty()) ejecutando = true;
    }

    /** Reinicio total: sin vehículos ni despachos, reloj en cero y contadores limpios. */
    public void limpiar() {
        detener();
        vehiculos.clear();
        despachos.clear();
        pasajerosTransportados = 0;
        segundosTranscurridos = 0;
        horaInicioMinutos = 0;
        ultimoMinutoFactorPunta = -1;
        ultimoMinutoOrganico = 0;
        generadorEventos.reiniciar();
        desviosCache.clear();
        demandaParadas.reiniciar();
    }

    /** Laboral, sábado, domingo o feriado: cambia la demanda, la congestión y los accidentes. */
    public void setTipoDia(EberthMTipoDia tipoDia) {
        this.tipoDia = Objects.requireNonNull(tipoDia);
        demandaParadas.setPerfil(parametrosCiudad, this.tipoDia);
        ultimoMinutoFactorPunta = -1; // fuerza recalcular la congestión con el nuevo día
    }

    public EberthMTipoDia tipoDia() {
        return tipoDia;
    }

    /** Enciende o apaga el generador orgánico; los eventos ya creados siguen hasta caducar. */
    public void setEventosOrganicos(boolean activos) {
        this.eventosOrganicos = activos;
    }

    public boolean eventosOrganicosActivos() {
        return eventosOrganicos;
    }

    /** Parámetros de la ciudad que alimentan al generador (se guarda una copia). */
    public void setParametrosCiudad(EberthMParametrosCiudad parametros) {
        this.parametrosCiudad = parametros == null ? null : parametros.copia();
        if (parametrosCiudad != null) {
            demandaParadas.setMaximoPorParada(parametrosCiudad.getCapacidadMaximaParada());
        }
        demandaParadas.setPerfil(parametrosCiudad, tipoDia);
    }

    /** Se invoca desde el hilo del motor; quien lo reciba debe pasar al hilo de la UI. */
    public void setOyenteEventosOrganicos(java.util.function.Consumer<CambioEventosOrganicos> oyente) {
        this.oyenteEventosOrganicos = oyente;
    }

    /** Una vez por cada minuto simulado completado: caducan los viejos y se sortean los nuevos. */
    private void generarEventosOrganicos() {
        int minutoActual = (int) (segundosTranscurridos / 60);
        while (ultimoMinutoOrganico < minutoActual) {
            ultimoMinutoOrganico++;
            List<EberthMEventoTrafico> expirados = generadorEventos.expirados(ultimoMinutoOrganico);
            List<EberthMEventoTrafico> nuevos = List.of();
            if (eventosOrganicos && parametrosCiudad != null) {
                boolean punta = horaPuntaForzada
                        || parametrosCiudad.esHoraPunta(horaInicioMinutos + ultimoMinutoOrganico, tipoDia);
                nuevos = generadorEventos.generar(ultimoMinutoOrganico, punta, parametrosCiudad, tipoDia);
            }
            if ((!nuevos.isEmpty() || !expirados.isEmpty()) && oyenteEventosOrganicos != null) {
                oyenteEventosOrganicos.accept(new CambioEventosOrganicos(nuevos, expirados));
            }
        }
    }

    /** POIs que pueden ser destino de los pasajeros; rige para los pasajeros que suban después. */
    public void setPuntosInteres(List<EberthMPoi> pois) {
        demandaParadas.setPois(new EberthMIndicePoi(List.copyOf(pois)));
    }

    public void configurarGrafo(PieroGGrafoVial grafo) {
        this.grafo = Objects.requireNonNull(grafo);
        this.pathfinder = new PieroGPathfinder(grafo);
        this.pathfinder.setModo(PieroGPathfinder.Modo.A_ESTRELLA);
        generadorEventos.setVias(grafo.getEdges());
    }

    public void actualizarServicios(List<EberthMServicioMicro> servicios) {
        Map<String, EberthMServicioMicro> porId = servicios.stream()
                .collect(java.util.stream.Collectors.toMap(EberthMServicioMicro::id, servicio -> servicio,
                        (primero, ultimo) -> ultimo));
        for (DespachoProgramado despacho : despachos) {
            EberthMServicioMicro actualizado = porId.get(despacho.despachador.servicio().id());
            if (actualizado != null) {
                despacho.despachador.actualizarServicio(actualizado);
                despacho.paradasIda = nodosParadas(actualizado.trayecto());
                despacho.paradasVuelta = paradasRetorno(actualizado);
                demandaParadas.registrar(despacho.paradasIda);
                demandaParadas.registrar(despacho.paradasVuelta);
            }
        }
        for (Vehiculo vehiculo : vehiculos) {
            EberthMServicioMicro actualizado = porId.get(vehiculo.idRuta);
            if (actualizado == null) continue;
            vehiculo.rutaAlternativa = vehiculo.enIda
                    ? actualizado.trayectoVuelta().edges() : actualizado.trayecto().edges();
            vehiculo.paradasIda = nodosParadas(actualizado.trayecto());
            vehiculo.paradasVuelta = paradasRetorno(actualizado);
            vehiculo.paradas = vehiculo.enIda
                    ? vehiculo.paradasIda : vehiculo.paradasVuelta;
        }
        invalidarRutas();
    }

    public void actualizarEventos(List<EberthMEventoTrafico> eventos) {
        this.eventos = List.copyOf(eventos);
        this.gasolineras = this.eventos.stream()
                .filter(evento -> evento.tipo() == EberthMTipoEvento.GASOLINERA)
                .toList();
        invalidarRutas();
    }

    private void invalidarRutas() {
        versionRed++;
        desviosCache.clear();
    }

    /**
     * Nodo de la ruta que corresponde a cada parada: el más cercano entre los que el
     * trayecto realmente recorre, avanzando en orden. Así coincide con el ajuste que
     * hizo el calculador de rutas y la parada siempre se alcanza.
     */
    /** Paradas de la vuelta; sin retorno la unidad regresa vacía y no recoge pasajeros. */
    private List<PieroGNodo> paradasRetorno(EberthMServicioMicro servicio) {
        return servicio.tieneRetorno() ? nodosParadas(servicio.trayectoVuelta()) : List.of();
    }

    private List<PieroGNodo> nodosParadas(LuisYTrayecto trayecto) {
        if (grafo == null) return List.of();
        List<PieroGEdge> edges = trayecto.edges();
        if (edges.isEmpty()) return List.of();
        List<PieroGNodo> nodosRuta = new ArrayList<>(edges.size() + 1);
        nodosRuta.add(edges.getFirst().origen);
        for (PieroGEdge e : edges) nodosRuta.add(e.destino);
        List<PieroGNodo> paradas = new ArrayList<>();
        int desde = 0;
        for (var punto : trayecto.puntos()) {
            int mejor = -1;
            double mejorD2 = Double.MAX_VALUE;
            for (int i = desde; i < nodosRuta.size(); i++) {
                PieroGNodo n = nodosRuta.get(i);
                double dx = (n.lon - punto.lon()) * Math.cos(Math.toRadians(punto.lat()));
                double dy = n.lat - punto.lat();
                double d2 = dx * dx + dy * dy;
                if (d2 < mejorD2) {
                    mejorD2 = d2;
                    mejor = i;
                }
            }
            if (mejor < 0) continue;
            paradas.add(nodosRuta.get(mejor));
            desde = mejor;
        }
        return List.copyOf(paradas);
    }

    public void avanzar(double deltaSegundos) {
        if (!ejecutando || !Double.isFinite(deltaSegundos) || deltaSegundos <= 0) return;
        double delta = Math.min(deltaSegundos, 1.0) * velocidadSimulacion;
        segundosTranscurridos += delta;
        calcularLimitesPorDistanciaSeguridad();
        for (Vehiculo vehiculo : vehiculos) {
            try {
                vehiculo.avanzar(delta, pathfinder, gasolineras, versionRed, desviosCache,
                        demandaParadas, segundosTranscurridos);
            } catch (RuntimeException ex) {
                vehiculo.estado = EberthMPosicionVehiculo.Estado.AVERIADO;
                LOG.log(Level.SEVERE, "Se aisló un error en el vehículo " + vehiculo.idRuta, ex);
            }
        }
        procesarDespachos();
        generarEventosOrganicos();
        demandaParadas.generar((int) (segundosTranscurridos / 60), minutoParaPerfil(), escalaDemanda, eventos);
        pasajerosTransportados += demandaParadas.retirarAbordados();
    }

    /**
     * Para cada vehículo calcula cuánto puede avanzar este paso sin acercarse a menos de
     * {@link #DISTANCIA_SEGURIDAD_METROS} del que va delante, en su misma vía o en la
     * siguiente de su ruta. Usa las posiciones previas al paso, así que no depende del orden.
     */
    private void calcularLimitesPorDistanciaSeguridad() {
        Map<PieroGEdge, List<Vehiculo>> porVia = new java.util.IdentityHashMap<>();
        for (Vehiculo v : vehiculos) {
            v.limiteAvance = Double.MAX_VALUE;
            if (v.indiceEdge < v.ruta.size() && !v.esperandoEnTerminal()) {
                porVia.computeIfAbsent(v.ruta.get(v.indiceEdge), k -> new ArrayList<>()).add(v);
            }
        }
        for (Map.Entry<PieroGEdge, List<Vehiculo>> entrada : porVia.entrySet()) {
            List<Vehiculo> fila = entrada.getValue();
            if (fila.size() > 1) fila.sort((a, b) -> Double.compare(b.progresoMetros, a.progresoMetros));
        }
        for (Map.Entry<PieroGEdge, List<Vehiculo>> entrada : porVia.entrySet()) {
            List<Vehiculo> fila = entrada.getValue();
            for (int k = 0; k < fila.size(); k++) {
                Vehiculo v = fila.get(k);
                double distancia;
                if (k > 0) {
                    distancia = fila.get(k - 1).progresoMetros - v.progresoMetros;
                } else if (v.indiceEdge + 1 < v.ruta.size()) {
                    List<Vehiculo> adelante = porVia.get(v.ruta.get(v.indiceEdge + 1));
                    if (adelante == null) continue;
                    distancia = entrada.getKey().longitudMetros() - v.progresoMetros
                            + adelante.getLast().progresoMetros;
                } else {
                    continue;
                }
                v.limiteAvance = Math.max(0, distancia - DISTANCIA_SEGURIDAD_METROS);
            }
        }
    }

    /** Paradas con la cantidad de pasajeros esperando (demanda agregada). */
    public List<EberthMParadaEspera> paradasEnEspera() {
        return demandaParadas.instantanea();
    }

    public List<EberthMPosicionVehiculo> posiciones() {
        return vehiculos.stream().map(Vehiculo::posicion).toList();
    }

    public boolean estaEjecutando() {
        return ejecutando;
    }

    public boolean haySimulacion() {
        return !vehiculos.isEmpty() || !despachos.isEmpty();
    }

    public int capacidadTotal() {
        return vehiculos.stream().mapToInt(v -> v.capacidad).sum();
    }

    public int pasajerosAsignados() {
        return vehiculos.stream().mapToInt(v -> v.pasajeros).sum();
    }

    public int pasajerosTransportados() {
        return pasajerosTransportados;
    }

    public double ocupacionPromedio() {
        return vehiculos.isEmpty() ? 0 : vehiculos.stream()
                .mapToDouble(v -> (double) v.pasajeros / v.capacidad).average().orElse(0);
    }

    public int vehiculosConIncidencia() {
        return (int) vehiculos.stream().filter(v -> v.estado == EberthMPosicionVehiculo.Estado.SIN_RUTA
                || v.estado == EberthMPosicionVehiculo.Estado.AVERIADO).count();
    }

    public double segundosTranscurridos() {
        return segundosTranscurridos;
    }

    public int horaActualMinutos() {
        return Math.floorMod(horaInicioMinutos + (int) (segundosTranscurridos / 60),
                24 * 60);
    }

    public int minutosHastaProximoDespacho() {
        return despachos.stream()
                .filter(d -> !d.despachador.terminado())
                .mapToInt(d -> d.despachador.minutosHastaProximoDespacho(segundosTranscurridos))
                .min().orElse(-1);
    }

    private void procesarDespachos() {
        for (DespachoProgramado despacho : despachos) {
            EberthMDespachador despachador = despacho.despachador;
            EberthMServicioMicro servicio = despachador.servicio();
            try {
                Optional<EberthMDespachador.Salida> pendiente;
                while ((pendiente = despachador.siguienteSalida(
                        segundosTranscurridos, horaActualMinutos())).isPresent()) {
                    EberthMDespachador.Salida salida = pendiente.get();
                    int abordados = salida.pasajeros();
                    boolean saleDelFinal = salida.desdeTerminalFinal();
                    Vehiculo vehiculo = new Vehiculo(
                            saleDelFinal ? servicio.trayectoVuelta().edges()
                                    : servicio.trayecto().edges(),
                            saleDelFinal ? servicio.trayecto().edges()
                                    : servicio.trayectoVuelta().edges(),
                            EberthMPosicionVehiculo.TipoVehiculo.MINIBUS, abordados, servicio.id(),
                            servicio.nombre(), servicio.color(),
                            servicio.frecuenciaMinutos() * 60.0,
                            despacho.paradasIda, despacho.paradasVuelta);
                    vehiculo.enIda = !saleDelFinal;
                    vehiculo.paradas = vehiculo.enIda
                            ? vehiculo.paradasIda : vehiculo.paradasVuelta;
                    vehiculo.asignarDestinos(abordados, demandaParadas);
                    vehiculos.add(vehiculo);
                    pasajerosTransportados += abordados;
                }
            } catch (RuntimeException ex) {
                despachador.finalizar();
                LOG.log(Level.SEVERE, "Se aisló un error en el despacho de " + servicio.nombre(),
                        ex);
            }
        }
        int horaActual = horaActualMinutos();
        if (horaActual == ultimoMinutoFactorPunta && horaPuntaForzada == ultimaHoraPuntaForzada) return;
        int minutoPerfil = minutoParaPerfil();
        double factorHoraPunta = EberthMPerfilHoraPunta.factor(minutoPerfil, tipoDia);
        if (grafo != null) {
            EberthMPerfilHoraPunta.aplicar(grafo, minutoPerfil, tipoDia);
        } else {
            for (DespachoProgramado despacho : despachos) {
                for (var edge : despacho.despachador.servicio().trayecto().edges()) {
                    edge.factorHoraPunta = factorHoraPunta;
                }
                for (var edge : despacho.despachador.servicio().trayectoVuelta().edges()) {
                    edge.factorHoraPunta = factorHoraPunta;
                }
            }
        }
        ultimoMinutoFactorPunta = horaActual;
        ultimaHoraPuntaForzada = horaPuntaForzada;
    }

    /** Minuto del día con el que se consulta el perfil de hora punta (18:00 si está forzada). */
    private int minutoParaPerfil() {
        int minuto = horaActualMinutos();
        return horaPuntaForzada && EberthMPerfilHoraPunta.factor(minuto) < MINUTO_HORA_PUNTA_FORZADA_FACTOR
                ? MINUTO_HORA_PUNTA_FORZADA : minuto;
    }

    /** Fuerza (o libera) la hora punta en toda la ciudad, sea cual sea la hora simulada. */
    public void setHoraPuntaForzada(boolean forzada) {
        this.horaPuntaForzada = forzada;
    }

    public boolean isHoraPuntaForzada() {
        return horaPuntaForzada;
    }

    private static int capacidad(EberthMPosicionVehiculo.TipoVehiculo tipo) {
        return tipo == EberthMPosicionVehiculo.TipoVehiculo.BUS ? 50 : 18;
    }

    public double getVelocidadSimulacion() {
        return velocidadSimulacion;
    }

    /**
     * Multiplica la demanda base de todas las rutas y el flujo que llega a cada parada. Se aplica
     * a las simulaciones que se inicien después; no cambia la que ya corre.
     */
    public void setEscalaDemanda(int escala) {
        if (escala < 1 || escala > MAX_ESCALA_DEMANDA) {
            throw new IllegalArgumentException("La escala de demanda debe estar entre 1 y "
                    + MAX_ESCALA_DEMANDA + ".");
        }
        this.escalaDemanda = escala;
    }

    public void setVelocidadSimulacion(double velocidadSimulacion) {
        if (!Double.isFinite(velocidadSimulacion)
                || velocidadSimulacion < 0.5 || velocidadSimulacion > 30) {
            throw new IllegalArgumentException("La velocidad debe estar entre 0.5x y 30x.");
        }
        this.velocidadSimulacion = velocidadSimulacion;
    }

    private static final class Vehiculo {
        private List<PieroGEdge> ruta;
        private List<PieroGEdge> rutaAlternativa;
        private List<PieroGNodo> paradasIda = List.of();
        private List<PieroGNodo> paradasVuelta = List.of();
        private List<PieroGNodo> paradas = List.of();
        private final EberthMPosicionVehiculo.TipoVehiculo tipo;
        private int pasajeros;
        /** Parada de destino → cantidad de pasajeros a bordo que bajan allí. */
        private final Map<PieroGNodo, Integer> destinos = new HashMap<>();
        /**
         * POI de destino → pasajeros a bordo que bajan al pasar a menos de 50 m. Solo contiene
         * los POIs con pasajeros, de modo que cada paso compara contra unos pocos puntos.
         */
        private final Map<EberthMPoi, Integer> destinosPoi = new HashMap<>();
        /** Punto de la ruta más cercano a cada POI destino: ahí bajan sus pasajeros y caminan el resto. */
        private final Map<EberthMPoi, PuntoBajada> puntosBajada = new HashMap<>();
        /** POIs a ≤ 400 m de {@link #rutaCandidatos}; se recalcula cuando cambia la ruta. */
        private List<EberthMIndicePoi.Candidato> candidatosPoi = List.of();
        private List<PieroGEdge> rutaCandidatos;
        private final int capacidad;
        private final String idRuta;
        private final String nombreRuta;
        private final String color;
        private final double esperaTerminalSegundos;
        private int indiceEdge;
        private int siguienteParada = 1;
        private double progresoMetros;
        private double esperaRestante;
        private boolean enIda = true;
        private EberthMPosicionVehiculo.Estado estado = EberthMPosicionVehiculo.Estado.EN_RUTA;
        private Set<String> gasolinerasVisitadas;
        private long versionRuta = -1;
        private double reintentoSinRuta;
        /** Al volver al terminal, sube a los que esperan allí cuando termina la espera. */
        private boolean abordajePendiente;
        /** Metros que puede avanzar en el paso actual sin invadir la distancia de seguridad. */
        private double limiteAvance = Double.MAX_VALUE;

        private Vehiculo(List<PieroGEdge> ruta, List<PieroGEdge> rutaAlternativa,
                         EberthMPosicionVehiculo.TipoVehiculo tipo, int pasajeros,
                         String idRuta, String nombreRuta, String color,
                         double esperaTerminalSegundos) {
            this(ruta, rutaAlternativa, tipo, pasajeros, idRuta, nombreRuta, color,
                    esperaTerminalSegundos, List.of(), List.of());
        }

        private Vehiculo(List<PieroGEdge> ruta, List<PieroGEdge> rutaAlternativa,
                         EberthMPosicionVehiculo.TipoVehiculo tipo, int pasajeros,
                         String idRuta, String nombreRuta, String color,
                         double esperaTerminalSegundos, List<PieroGNodo> paradasIda,
                         List<PieroGNodo> paradasVuelta) {
            this.ruta = List.copyOf(ruta);
            this.rutaAlternativa = List.copyOf(rutaAlternativa);
            this.paradasIda = List.copyOf(paradasIda);
            this.paradasVuelta = List.copyOf(paradasVuelta);
            this.paradas = this.paradasIda;
            this.tipo = tipo;
            this.pasajeros = pasajeros;
            this.capacidad = capacidad(tipo);
            this.idRuta = idRuta;
            this.nombreRuta = nombreRuta;
            this.color = color;
            this.esperaTerminalSegundos = esperaTerminalSegundos;
        }

        /** Esperando su hora de salida en el terminal: no bloquea a las unidades que salen. */
        private boolean esperandoEnTerminal() {
            return abordajePendiente || (esperaRestante > 0 && indiceEdge == 0 && progresoMetros == 0
                    && estado == EberthMPosicionVehiculo.Estado.EN_RUTA);
        }

        private void avanzar(double segundos, PieroGPathfinder pathfinder,
                             List<EberthMEventoTrafico> eventos, long versionRed,
                             Map<RutaDesvioKey, List<PieroGEdge>> desviosCache,
                             DemandaParadas demanda, double reloj) {
            if (versionRuta != versionRed || hayViaCerradaAdelante()) {
                recalcularSiCerrada(pathfinder, versionRed, desviosCache);
            }
            if (estado == EberthMPosicionVehiculo.Estado.SIN_RUTA) {
                // Nunca queda errante: reintenta mientras no exista desvío.
                reintentoSinRuta -= segundos;
                if (reintentoSinRuta <= 0) {
                    reintentoSinRuta = REINTENTO_SIN_RUTA_SEGUNDOS;
                    recalcularSiCerrada(pathfinder, versionRed, desviosCache);
                }
            }
            if (estado == EberthMPosicionVehiculo.Estado.SIN_RUTA
                    || estado == EberthMPosicionVehiculo.Estado.AVERIADO) return;
            detectarGasolinera(eventos);
            double restante = segundos;
            double avanceTick = 0;
            if (esperaRestante > 0) {
                double espera = Math.min(esperaRestante, restante);
                esperaRestante -= espera;
                restante -= espera;
            }
            if (abordajePendiente && esperaRestante <= 0) {
                abordajePendiente = false;
                abordarEnTerminal(demanda);
            }
            if (esperaRestante <= 0 && estado == EberthMPosicionVehiculo.Estado.EN_PARADA) {
                estado = EberthMPosicionVehiculo.Estado.EN_RUTA;
            }
            if (esperaRestante <= 0 && estado == EberthMPosicionVehiculo.Estado.DETENIDO) {
                estado = EberthMPosicionVehiculo.Estado.EN_RUTA;
            }
            while (restante > 0 && indiceEdge < ruta.size()) {
                PieroGEdge edge = ruta.get(indiceEdge);
                if (edge.cerrado) {
                    // La vía bajo el vehículo se cerró: vuelve al inicio del tramo y recalcula.
                    progresoMetros = 0;
                    estado = EberthMPosicionVehiculo.Estado.SIN_RUTA;
                    reintentoSinRuta = REINTENTO_SIN_RUTA_SEGUNDOS;
                    recalcularSiCerrada(pathfinder, versionRed, desviosCache);
                    if (estado == EberthMPosicionVehiculo.Estado.SIN_RUTA) break;
                    continue;
                }
                double velocidad = edge.velocidadLibreMps()
                        / Math.max(1.0, edge.factorTrafico * edge.factorHoraPunta);
                if (!eventos.isEmpty() && bajoLluvia(edge, eventos)) {
                    velocidad *= EberthMTipoEvento.LLUVIA.multiplicadorVelocidad();
                }
                velocidad = Math.max(1.0, velocidad);
                double disponible = Math.max(0, edge.longitudMetros() - progresoMetros);
                double libre = disponible;
                EberthMSemaforo semaforo = edge.destino.semaforo;
                if (edge.destino.semaforoRoto || (semaforo != null && semaforo.esRojo(reloj))) {
                    // Con luz roja (o semáforo roto: rojo permanente) se frena DISTANCIA_FRENADO_SEMAFORO_METROS antes del nodo.
                    libre = Math.min(libre, Math.max(0,
                            edge.longitudMetros() - DISTANCIA_FRENADO_SEMAFORO_METROS - progresoMetros));
                }
                // Con otro vehículo a menos de la distancia de seguridad, se frena detrás de él.
                libre = Math.min(libre, Math.max(0, limiteAvance - avanceTick));
                if (libre < disponible) {
                    disponible = libre;
                    if (disponible <= 1e-9) {
                        estado = EberthMPosicionVehiculo.Estado.DETENIDO;
                        break;
                    }
                }
                double recorrido = Math.min(disponible, restante * velocidad);
                double progresoPrevio = progresoMetros;
                progresoMetros += recorrido;
                if (recorrido > 0) bajarEnPois(edge, progresoPrevio, progresoMetros);
                avanceTick += recorrido;
                restante -= recorrido / velocidad;
                if (progresoMetros >= edge.longitudMetros()) {
                    boolean paradaAlcanzada = siguienteParada < paradas.size()
                            && edge.destino == paradas.get(siguienteParada);
                    if (paradaAlcanzada) {
                        siguienteParada++;
                        // Dos paradas seguidas sobre el mismo nodo se atienden juntas.
                        while (siguienteParada < paradas.size()
                                && paradas.get(siguienteParada) == edge.destino) siguienteParada++;
                        intercambiarPasajeros(edge.destino, demanda);
                        estado = EberthMPosicionVehiculo.Estado.EN_PARADA;
                        esperaRestante = Math.max(esperaRestante, 15);
                    }
                    indiceEdge++;
                    progresoMetros = 0;
                    if (indiceEdge >= ruta.size()) {
                        pasajeros = 0; // en el terminal bajan todos
                        destinos.clear();
                        destinosPoi.clear();
                        puntosBajada.clear();
                        ruta = rutaAlternativa;
                        enIda = !enIda;
                        paradas = enIda ? paradasIda : paradasVuelta;
                        siguienteParada = 1;
                        abordajePendiente = !paradas.isEmpty();
                        versionRuta = -1; // la nueva ruta se revisa por completo en el próximo paso
                        indiceEdge = 0;
                        esperaRestante = esperaTerminalSegundos;
                        estado = EberthMPosicionVehiculo.Estado.EN_RUTA;
                        break;
                    }
                    if (paradaAlcanzada) break;
                }
            }
        }

        /** Posición actual (interpolada sobre el tramo) dentro del radio de alguna lluvia. */
        private boolean bajoLluvia(PieroGEdge edge, List<EberthMEventoTrafico> eventos) {
            double f = Math.min(1, progresoMetros / Math.max(0.01, edge.longitudMetros()));
            double lon = edge.origen.lon + (edge.destino.lon - edge.origen.lon) * f;
            double lat = edge.origen.lat + (edge.destino.lat - edge.origen.lat) * f;
            return EberthMAplicadorEventos.estaBajoLluvia(lon, lat, eventos);
        }

        private void abordarEnTerminal(DemandaParadas demanda) {
            if (paradas.size() < 2) return;
            int suben = demanda.abordar(paradas.getFirst(), capacidad - pasajeros);
            pasajeros += suben;
            asignarDestinos(suben, demanda);
        }

        /** En una parada primero bajan los que van allí y luego suben los que esperan. */
        private void intercambiarPasajeros(PieroGNodo parada, DemandaParadas demanda) {
            Integer bajan = destinos.remove(parada);
            if (bajan != null) pasajeros -= bajan;
            int futuras = paradas.size() - siguienteParada;
            if (futuras <= 0) return; // última parada: nadie sube, no tendría destino
            int suben = demanda.abordar(parada, capacidad - pasajeros);
            pasajeros += suben;
            asignarDestinos(suben, demanda);
        }

        /**
         * Reparte {@code cantidad} pasajeros: a un POI aleatorio a ≤ 400 m de lo que le falta de
         * ruta o, si no hay ninguno, a una de las paradas que aún le faltan al vehículo.
         */
        private void asignarDestinos(int cantidad, DemandaParadas demanda) {
            int futuras = paradas.size() - siguienteParada;
            if (cantidad <= 0) return;
            List<EberthMIndicePoi.Candidato> adelante = candidatosAdelante(demanda.pois());
            double[] pos = adelante.isEmpty() ? null : posicionActual();
            for (int i = 0; i < cantidad; i++) {
                EberthMPoi poi = adelante.isEmpty() ? null
                        : adelante.get(demanda.sorteo(adelante.size())).poi();
                // Un POI pegado al punto de subida bajaría al pasajero de inmediato: se descarta.
                if (poi != null && EberthMIndicePoi.distanciaPuntoSegmento(poi.lon(), poi.lat(),
                        pos[0], pos[1], pos[0], pos[1]) > EberthMIndicePoi.RADIO_BAJADA_METROS) {
                    destinosPoi.merge(poi, 1, Integer::sum);
                    puntosBajada.computeIfAbsent(poi, this::puntoMasCercanoEnRuta);
                } else if (futuras > 0) {
                    destinos.merge(paradas.get(siguienteParada + demanda.sorteo(futuras)),
                            1, Integer::sum);
                }
            }
        }

        /** POIs alcanzables desde el tramo actual en adelante (sublista de la caché de la ruta). */
        private List<EberthMIndicePoi.Candidato> candidatosAdelante(EberthMIndicePoi indice) {
            if (indice.estaVacio() || ruta.isEmpty()) return List.of();
            if (rutaCandidatos != ruta) {
                candidatosPoi = indice.candidatosEnRuta(ruta);
                rutaCandidatos = ruta;
            }
            int lo = 0, hi = candidatosPoi.size();
            while (lo < hi) { // primer candidato con indiceEdge >= tramo actual
                int mid = (lo + hi) >>> 1;
                if (candidatosPoi.get(mid).indiceEdge() < indiceEdge) lo = mid + 1; else hi = mid;
            }
            return candidatosPoi.subList(lo, candidatosPoi.size());
        }

        private double[] posicionActual() {
            PieroGEdge edge = ruta.get(Math.min(indiceEdge, ruta.size() - 1));
            double f = Math.min(1, progresoMetros / Math.max(0.01, edge.longitudMetros()));
            return new double[]{edge.origen.lon + (edge.destino.lon - edge.origen.lon) * f,
                    edge.origen.lat + (edge.destino.lat - edge.origen.lat) * f};
        }

        /** Tramo de la ruta (y metros dentro de él) donde el micro pasa más cerca del POI. */
        private record PuntoBajada(PieroGEdge edge, double metros) {}

        /**
         * Busca, de la posición actual en adelante, el punto de la ruta más cercano al POI (a
         * igualdad de distancia, el primero). Allí bajan los pasajeros aunque el POI quede a
         * varias cuadras: se asume que caminan el resto.
         */
        private PuntoBajada puntoMasCercanoEnRuta(EberthMPoi poi) {
            PuntoBajada mejor = null;
            double mejorDistancia = Double.MAX_VALUE;
            for (int i = indiceEdge; i < ruta.size(); i++) {
                PieroGEdge e = ruta.get(i);
                double largo = Math.max(0.01, e.longitudMetros());
                double desde = i == indiceEdge ? Math.min(1, progresoMetros / largo) : 0;
                double aLon = e.origen.lon + (e.destino.lon - e.origen.lon) * desde;
                double aLat = e.origen.lat + (e.destino.lat - e.origen.lat) * desde;
                double[] p = EberthMIndicePoi.proyectarEnSegmento(poi.lon(), poi.lat(),
                        aLon, aLat, e.destino.lon, e.destino.lat);
                if (p[0] < mejorDistancia - 1.0) { // 1 m de tolerancia: gana el tramo más temprano
                    mejorDistancia = p[0];
                    mejor = new PuntoBajada(e, (desde + p[1] * (1 - desde)) * largo);
                }
            }
            return mejor;
        }

        /**
         * Baja a los pasajeros cuyo POI destino queda a menos de 50 m del trayecto recorrido en
         * este paso (segmento entre ambos progresos, para no saltarse un POI a alta velocidad) o
         * cuyo punto de la ruta más cercano ({@link #puntosBajada}) se cruzó en este paso.
         */
        private void bajarEnPois(PieroGEdge edge, double desdeMetros, double hastaMetros) {
            if (destinosPoi.isEmpty()) return;
            double largo = Math.max(0.01, edge.longitudMetros());
            double f0 = Math.min(1, desdeMetros / largo), f1 = Math.min(1, hastaMetros / largo);
            double dLon = edge.destino.lon - edge.origen.lon, dLat = edge.destino.lat - edge.origen.lat;
            double aLon = edge.origen.lon + dLon * f0, aLat = edge.origen.lat + dLat * f0;
            double bLon = edge.origen.lon + dLon * f1, bLat = edge.origen.lat + dLat * f1;
            var it = destinosPoi.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                EberthMPoi poi = e.getKey();
                PuntoBajada punto = puntosBajada.get(poi);
                boolean cruzado = punto != null && punto.edge() == edge
                        && punto.metros() >= desdeMetros && punto.metros() <= hastaMetros;
                if (cruzado || EberthMIndicePoi.distanciaPuntoSegmento(poi.lon(), poi.lat(), aLon, aLat,
                        bLon, bLat) <= EberthMIndicePoi.RADIO_BAJADA_METROS) {
                    pasajeros -= e.getValue();
                    it.remove();
                    puntosBajada.remove(poi);
                }
            }
        }

        private void detectarGasolinera(List<EberthMEventoTrafico> eventos) {
            if (indiceEdge >= ruta.size()) return;
            PieroGEdge edge = ruta.get(indiceEdge);
            for (EberthMEventoTrafico evento : eventos) {
                String id = evento.nombre() + ":" + evento.lon() + ":" + evento.lat();
                if ((gasolinerasVisitadas != null && gasolinerasVisitadas.contains(id))
                        || distanciaPuntoTramo(evento.lon(), evento.lat(), edge) > 12) continue;
                if (gasolinerasVisitadas == null) gasolinerasVisitadas = new HashSet<>();
                gasolinerasVisitadas.add(id);
                esperaRestante = Math.max(esperaRestante, 180);
                estado = EberthMPosicionVehiculo.Estado.DETENIDO;
            }
        }

        private static double distanciaPuntoTramo(double lon, double lat, PieroGEdge edge) {
            double escalaX = 111_320 * Math.cos(Math.toRadians(lat));
            double ax = edge.origen.lon * escalaX;
            double ay = edge.origen.lat * 111_320;
            double bx = edge.destino.lon * escalaX;
            double by = edge.destino.lat * 111_320;
            double px = lon * escalaX;
            double py = lat * 111_320;
            double dx = bx - ax;
            double dy = by - ay;
            double longitud2 = dx * dx + dy * dy;
            double t = longitud2 == 0 ? 0
                    : Math.clamp(((px - ax) * dx + (py - ay) * dy) / longitud2, 0, 1);
            return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
        }

        /**
         * Si hay un tramo cerrado por delante, calcula con A* un desvío solo hasta la
         * siguiente parada posterior al bloqueo y reengancha el resto de la ruta original.
         */
        private void recalcularSiCerrada(PieroGPathfinder pathfinder, long versionRed,
                                         Map<RutaDesvioKey, List<PieroGEdge>> desviosCache) {
            this.versionRuta = versionRed;
            if (pathfinder == null || ruta.isEmpty() || indiceEdge >= ruta.size()) return;
            for (int intento = 0; intento < MAX_DESVIOS_POR_PASO; intento++) {
                int bloqueada = -1;
                for (int i = indiceEdge; i < ruta.size(); i++) {
                    if (ruta.get(i).cerrado) {
                        bloqueada = i;
                        break;
                    }
                }
                if (bloqueada < 0) {
                    if (estado == EberthMPosicionVehiculo.Estado.SIN_RUTA) {
                        estado = EberthMPosicionVehiculo.Estado.EN_RUTA;
                    }
                    return;
                }
                if (!desviarHastaSiguienteParada(pathfinder, bloqueada, desviosCache)) {
                    estado = EberthMPosicionVehiculo.Estado.SIN_RUTA;
                    return;
                }
            }
        }

        /** Revisa las próximas vías de la ruta: el vehículo reacciona antes de llegar al bloqueo. */
        private boolean hayViaCerradaAdelante() {
            int hasta = Math.min(ruta.size(), indiceEdge + VIAS_DE_ANTICIPACION);
            for (int i = indiceEdge; i < hasta; i++) {
                if (ruta.get(i).cerrado) return true;
            }
            return false;
        }

        /**
         * Ruta de emergencia: desde el nodo más cercano de la posición actual (o desde la parada
         * pendiente que aún debe atender antes del bloqueo) hasta la primera parada pendiente
         * posterior al bloqueo. Después se retoma tal cual la ruta original.
         */
        private boolean desviarHastaSiguienteParada(PieroGPathfinder pathfinder, int bloqueada,
                                                    Map<RutaDesvioKey, List<PieroGEdge>> desviosCache) {
            int finBloqueo = bloqueada;
            while (finBloqueo + 1 < ruta.size() && ruta.get(finBloqueo + 1).cerrado) finBloqueo++;
            // Destino del desvío: primera parada pendiente tras el bloqueo; sin paradas, justo después.
            int reenganche = finBloqueo;
            for (int i = bloqueada; i < ruta.size(); i++) {
                if (esParadaPendiente(ruta.get(i).destino)) {
                    reenganche = i;
                    break;
                }
            }
            // Las paradas pendientes anteriores al bloqueo se atienden por la ruta original.
            int prefijo = -1;
            for (int i = bloqueada - 1; i >= indiceEdge; i--) {
                if (esParadaPendiente(ruta.get(i).destino)) {
                    prefijo = i;
                    break;
                }
            }
            PieroGNodo inicio;
            boolean salto = false;
            if (prefijo >= 0) {
                inicio = ruta.get(prefijo).destino;
            } else if (bloqueada == indiceEdge) {
                // Justo sobre la vía bloqueada: salta al extremo más cercano de esa vía.
                PieroGEdge actual = ruta.get(indiceEdge);
                boolean cercaDelFinal = progresoMetros >= actual.longitudMetros() / 2;
                inicio = cercaDelFinal ? actual.destino : actual.origen;
                prefijo = indiceEdge - 1;
                salto = true;
            } else {
                inicio = ruta.get(indiceEdge).destino;
                prefijo = indiceEdge;
            }
            PieroGNodo objetivo = ruta.get(reenganche).destino;
            RutaDesvioKey clave = new RutaDesvioKey(idRuta, inicio.id, objetivo.id,
                    siguienteParada, enIda);
            List<PieroGEdge> desvio = desviosCache.get(clave);
            if (desvio == null) {
                List<PieroGEdge> referencia = ruta.subList(prefijo + 1, reenganche + 1);
                PieroGRutaResult resultado = null;
                // Primero cerca de la ruta original (≈ 2 cuadras); solo si no hay paso se amplía.
                for (double radio : RADIOS_DESVIO_METROS) {
                    resultado = pathfinder.rutaCercana(inicio, objetivo, referencia, radio);
                    if (resultado != null) break;
                }
                if (resultado == null) resultado = pathfinder.ruta(inicio, objetivo);
                if (resultado == null) return false;
                desvio = resultado.getEdges();
                if (desviosCache.size() >= MAX_DESVIOS_CACHE) desviosCache.clear();
                desviosCache.put(clave, desvio);
            }
            List<PieroGEdge> nuevaRuta = new ArrayList<>(ruta.subList(0, prefijo + 1));
            nuevaRuta.addAll(desvio);
            nuevaRuta.addAll(ruta.subList(reenganche + 1, ruta.size()));
            if (nuevaRuta.isEmpty()) return false;
            if (salto) {
                indiceEdge = prefijo + 1;
                progresoMetros = 0;
            }
            ruta = List.copyOf(nuevaRuta);
            estado = EberthMPosicionVehiculo.Estado.EN_RUTA;
            LOG.info(() -> "Ruta de emergencia para " + idRuta + " hasta la siguiente parada");
            return true;
        }

        private boolean esParadaPendiente(PieroGNodo nodo) {
            for (int i = siguienteParada; i < paradas.size(); i++) {
                if (paradas.get(i) == nodo) return true;
            }
            return false;
        }

        private EberthMPosicionVehiculo posicion() {
            PieroGEdge edge = ruta.get(Math.min(indiceEdge, ruta.size() - 1));
            double fraccion = Math.min(1, progresoMetros / Math.max(0.01, edge.longitudMetros()));
            PieroGNodo origen = edge.origen;
            PieroGNodo destino = edge.destino;
            double dx = destino.x - origen.x;
            double dy = destino.y - origen.y;
            double largo = Math.hypot(dx, dy);
            return new EberthMPosicionVehiculo(origen.x + dx * fraccion,
                    origen.y + dy * fraccion, tipo, pasajeros, capacidad,
                    idRuta, nombreRuta, color, estado,
                    largo > 0 ? dx / largo : 0, largo > 0 ? dy / largo : 0);
        }

    }

    private static final class DespachoProgramado {
        private final EberthMDespachador despachador;
        private List<PieroGNodo> paradasIda;
        private List<PieroGNodo> paradasVuelta;

        private DespachoProgramado(EberthMServicioMicro servicio, int escalaDemanda) {
            this.despachador = new EberthMDespachador(servicio, escalaDemanda);
        }
    }

    /**
     * Demanda agregada: un contador entero de pasajeros esperando por parada
     * (clave: id del nodo), sin simular personas individuales.
     */
    private static final class DemandaParadas {
        private static final int PASAJEROS_POR_MINUTO = 5;
        private int maximoPorParada = EberthMParametrosCiudad.CAPACIDAD_PARADA_POR_DEFECTO;
        private final Map<Integer, Integer> esperando = new HashMap<>();
        private final Map<Integer, PieroGNodo> nodos = new HashMap<>();
        private int ultimoMinuto;
        private int abordadosSinContar;
        private final java.util.Random azar = new java.util.Random();

        void reiniciar() {
            esperando.clear();
            nodos.clear();
            ultimoMinuto = 0;
            abordadosSinContar = 0;
        }

        void setMaximoPorParada(int maximo) {
            this.maximoPorParada = Math.max(1, maximo);
        }

        void registrar(List<PieroGNodo> paradas) {
            for (PieroGNodo nodo : paradas) {
                nodos.putIfAbsent(nodo.id, nodo);
                esperando.putIfAbsent(nodo.id, 0);
            }
        }

        /** Radio alrededor de la parada en que un POI influye en su demanda (≈ 2 cuadras). */
        private static final double RADIO_INFLUENCIA_POI_METROS = 250;
        private EberthMParametrosCiudad perfilCiudad;
        private EberthMTipoDia perfilDia = EberthMTipoDia.LABORAL;
        /** Cuántos POIs de cada categoría hay cerca de cada parada (caché; se vacía al cambiar POIs). */
        private final Map<Integer, int[]> poisCercanos = new HashMap<>();

        void setPerfil(EberthMParametrosCiudad ciudad, EberthMTipoDia dia) {
            this.perfilCiudad = ciudad;
            this.perfilDia = dia;
        }

        private int[] categoriasCercanas(PieroGNodo nodo) {
            return poisCercanos.computeIfAbsent(nodo.id, id -> {
                int[] cuenta = new int[EberthMCategoriaPoi.values().length];
                for (EberthMPoi poi : pois.cercanos(nodo.lon, nodo.lat, RADIO_INFLUENCIA_POI_METROS)) {
                    cuenta[poi.categoria().ordinal()]++;
                }
                return cuenta;
            });
        }

        /**
         * Pasajeros nuevos por minuto en cada parada: base × escala × factor de la parada. El factor
         * es el promedio del perfil horario de los POIs cercanos (según hora, tipo de día y
         * demografía); sin POIs cercanos rige el perfil general. Una parada llena no recibe más.
         */
        void generar(int minutoSimulado, int minutoDelDia, int escala,
                     List<EberthMEventoTrafico> eventos) {
            if (minutoSimulado <= ultimoMinuto) return;
            int minutos = minutoSimulado - ultimoMinuto;
            ultimoMinuto = minutoSimulado;
            double[] porCategoria = new double[EberthMCategoriaPoi.values().length];
            for (EberthMCategoriaPoi c : EberthMCategoriaPoi.values()) {
                porCategoria[c.ordinal()] = EberthMPerfilDemanda.factor(c, minutoDelDia, perfilDia,
                        perfilCiudad);
            }
            double general = EberthMPerfilDemanda.factorGeneral(minutoDelDia, perfilDia);
            // Tope literal por parada: quien no cabe se rinde y camina a otra parada.
            long tope = maximoPorParada;
            double base = (double) minutos * PASAJEROS_POR_MINUTO * escala;
            esperando.replaceAll((id, n) -> {
                if (n >= tope) return n;
                double factor = general;
                if (!pois.estaVacio()) {
                    int[] cuenta = categoriasCercanas(nodos.get(id));
                    int total = 0;
                    double suma = 0;
                    for (int i = 0; i < cuenta.length; i++) {
                        total += cuenta[i];
                        suma += cuenta[i] * porCategoria[i];
                    }
                    if (total > 0) factor = suma / total;
                }
                // Vendedores ambulantes cerca de la parada: aglomeración (+200 %).
                double aglomeracion = eventos.isEmpty() ? 1.0
                        : EberthMAplicadorEventos.multiplicadorDemanda(nodos.get(id), eventos);
                // long: con escala alta y tiempo acelerado el producto no cabe en un int.
                long nuevos = Math.round(base * factor * aglomeracion);
                return (int) Math.min(tope, n + Math.max(0, nuevos));
            });
        }

        /** Sube hasta {@code libres} pasajeros; el resto sigue esperando. */
        int abordar(PieroGNodo parada, int libres) {
            Integer n = esperando.get(parada.id);
            if (n == null || libres <= 0) return 0;
            int suben = Math.min(n, libres);
            esperando.put(parada.id, n - suben);
            abordadosSinContar += suben;
            return suben;
        }

        private EberthMIndicePoi pois = EberthMIndicePoi.VACIO;

        EberthMIndicePoi pois() {
            return pois;
        }

        void setPois(EberthMIndicePoi pois) {
            this.pois = pois;
            poisCercanos.clear();
        }

        /** Índice aleatorio en {@code [0, n)} para elegir destinos. */
        int sorteo(int n) {
            return azar.nextInt(n);
        }

        int retirarAbordados() {
            int n = abordadosSinContar;
            abordadosSinContar = 0;
            return n;
        }

        List<EberthMParadaEspera> instantanea() {
            List<EberthMParadaEspera> lista = new ArrayList<>(esperando.size());
            esperando.forEach((id, n) -> {
                PieroGNodo nodo = nodos.get(id);
                lista.add(new EberthMParadaEspera(nodo.x, nodo.y, n));
            });
            return lista;
        }
    }

    private record RutaDesvioKey(String rutaId, int origenId, int destinoId,
                                 int siguienteParada, boolean ida) {}
}
