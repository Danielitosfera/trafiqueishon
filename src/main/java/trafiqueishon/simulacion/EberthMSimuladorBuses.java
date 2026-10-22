package trafiqueishon.simulacion;

import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.geo.PieroGPathfinder;
import trafiqueishon.geo.PieroGRutaResult;
import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGNodo;
import trafiqueishon.ruta.LuisYPaletaColores;
import trafiqueishon.ruta.LuisYTrayecto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class EberthMSimuladorBuses {
    public static final int MAX_UNIDADES = EberthMServicioMicro.MAX_UNIDADES;
    private static final Logger LOG = Logger.getLogger(EberthMSimuladorBuses.class.getName());
    private static final int MAX_DESVIOS_CACHE = 2_048;
    /** Cuánto debe acercar un micro al pasajero para que suba aunque no lo deje en su destino. */
    private static final double ACERCAMIENTO_TRANSBORDO_METROS = 150;
    /** Con la parada llena de gente el micro admite hasta un 20 % más de su capacidad (pasajeros apretados). */
    private static final double FACTOR_SOBRECUPO = 1.2;
    /** Más lejos que esto del destino al bajar: el pasajero busca un segundo micro. */
    private static final double RADIO_TRANSBORDO_METROS = EberthMIndicePoi.RADIO_CAMINATA_METROS;
    private static final double REINTENTO_SIN_RUTA_SEGUNDOS = 2.0;
    private static final int MAX_DESVIOS_POR_PASO = 16;
    /** Búsquedas A* de desvío nuevas por paso de simulación: reparte el costo y evita tirones. */
    private static final int MAX_BUSQUEDAS_DESVIO_POR_PASO = 1;
    private static final double DISTANCIA_FRENADO_SEMAFORO_METROS = 15.0;
    private static final double DISTANCIA_SEGURIDAD_METROS = 15.0;
    /** Fracción de la velocidad que conserva un micro en una vía saturada (tráfico denso). */
    private static final double FACTOR_VELOCIDAD_VIA_SATURADA = 0.3;
    private final FlujoVias flujoVias = new FlujoVias();
    /** Cuántas vías por delante revisa cada vehículo para enterarse de un bloqueo a tiempo. */
    private static final int VIAS_DE_ANTICIPACION = 6;
    /** Radios (≈ 2 cuadras, luego más) del corredor en que se busca el desvío. */
    private static final double[] RADIOS_DESVIO_METROS = {200, 500};
    private final List<Vehiculo> vehiculos = new ArrayList<>();
    private final List<DespachoProgramado> despachos = new ArrayList<>();
    private PieroGGrafoVial grafo;
    /** Demanda del día generada sin rutas; si existe, la gente nace de sus zonas en lugar de repartirse por igual. */
    private EberthMDemandaCiudad demandaCiudad;
    private PieroGPathfinder pathfinder;
    private List<EberthMEventoTrafico> eventos = List.of();
    private List<EberthMEventoTrafico> gasolineras = List.of();
    private final Map<RutaDesvioKey, List<PieroGEdge>> desviosCache = new HashMap<>();
    private final PresupuestoDesvios presupuestoDesvios = new PresupuestoDesvios();
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
    private volatile boolean policiasDinamicos = true;
    /** Vehículos detenidos en una misma vía a partir de los cuales llega un policía. */
    public static final int COLA_PARA_POLICIA = 4;
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
        long capacidadFlota = (long) buses * 50 + (long) micros * EberthMPosicionVehiculo.CAPACIDAD_MICRO;
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
        flujoVias.limpiar();
        demandaParadas.setDemandaZonas(demandaCiudad, grafo);
        demandaParadas.reiniciar();
        // La demanda de cada ruta sale de la ciudad: población × % transporte público, repartida
        // entre las rutas. Sin parámetros de ciudad rige el valor guardado en la ruta.
        long demandaRutaHora = demandaParadas.perfilCiudad == null ? -1
                : Math.round(demandaParadas.demandaCiudadPorMinuto() * 60 / servicios.size());
        for (EberthMServicioMicro servicio : servicios) {
            DespachoProgramado despacho = new DespachoProgramado(servicio,
                    demandaRutaHora < 0 ? servicio.pasajerosEsperando() : demandaRutaHora,
                    escalaDemanda);
            despacho.paradasIda = nodosParadas(servicio.trayecto());
            despacho.paradasVuelta = paradasRetorno(servicio);
            demandaParadas.registrarParadasRuta(despacho.paradasIda);
            demandaParadas.registrarParadasRuta(despacho.paradasVuelta);
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
        flujoVias.limpiar();
        desviosCache.clear();
        presupuestoDesvios.pendientes.clear();
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

    /** Activa la llegada automática de policías a las intersecciones con cola; los vigentes caducan solos. */
    public void setPoliciasDinamicos(boolean activos) {
        this.policiasDinamicos = activos;
    }

    public boolean policiasDinamicosActivos() {
        return policiasDinamicos;
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
            if (policiasDinamicos) {
                List<EberthMEventoTrafico> policias = desplegarPolicias(ultimoMinutoOrganico);
                if (!policias.isEmpty()) {
                    nuevos = new ArrayList<>(nuevos);
                    nuevos.addAll(policias);
                }
            }
            if ((!nuevos.isEmpty() || !expirados.isEmpty()) && oyenteEventosOrganicos != null) {
                oyenteEventosOrganicos.accept(new CambioEventosOrganicos(nuevos, expirados));
            }
        }
    }

    /**
     * Envía un policía a cada intersección cuya vía de acceso tenga más de
     * {@link #COLA_PARA_POLICIA} vehículos detenidos y aún no tenga uno.
     */
    private List<EberthMEventoTrafico> desplegarPolicias(int minuto) {
        Map<PieroGEdge, Integer> detenidos = new java.util.IdentityHashMap<>();
        for (Vehiculo v : vehiculos) {
            if (v.estado != EberthMPosicionVehiculo.Estado.DETENIDO || v.indiceEdge >= v.ruta.size()) continue;
            detenidos.merge(v.ruta.get(v.indiceEdge), 1, Integer::sum);
        }
        List<EberthMEventoTrafico> nuevos = new ArrayList<>();
        for (Map.Entry<PieroGEdge, Integer> cola : detenidos.entrySet()) {
            if (cola.getValue() <= COLA_PARA_POLICIA) continue;
            PieroGNodo nodo = cola.getKey().destino;
            if (generadorEventos.hayPoliciaEn(nodo.lon, nodo.lat)) continue;
            // Guía a los vehículos de la cola en el sentido en que circulan.
            PieroGEdge via = cola.getKey();
            double rumbo = Math.atan2(via.destino.y - via.origen.y, via.destino.x - via.origen.x);
            nuevos.add(generadorEventos.crearPolicia(nodo.lon, nodo.lat, minuto, rumbo));
        }
        return nuevos;
    }

    /** {@code true} si ninguna vía de la ruta supera hoy su flujo máximo de micros por hora. */
    private boolean rutaSinSaturar(List<PieroGEdge> ruta) {
        for (PieroGEdge edge : ruta) {
            if (flujoVias.saturada(edge, segundosTranscurridos)) return false;
        }
        return true;
    }

    /**
     * Micros que entraron a cada vía en la última hora simulada (6 cubetas de 10 min). Una vía
     * está saturada cuando ese flujo supera su {@link PieroGEdge#flujoMaximo}. Solo guarda las
     * vías recorridas; se toca únicamente desde el hilo del motor.
     */
    private static final class FlujoVias {
        private static final int CUBETAS = 6;
        private static final double SEGUNDOS_CUBETA = 600;
        /** Por vía: las {@link #CUBETAS} cuentas y, al final, el índice absoluto de la última cubeta. */
        private final Map<PieroGEdge, long[]> porVia = new java.util.IdentityHashMap<>();

        void limpiar() {
            porVia.clear();
        }

        void registrarEntrada(PieroGEdge edge, double reloj) {
            long[] datos = porVia.computeIfAbsent(edge, k -> new long[CUBETAS + 1]);
            avanzar(datos, reloj);
            datos[(int) (datos[CUBETAS] % CUBETAS)]++;
        }

        int vehiculosPorHora(PieroGEdge edge, double reloj) {
            long[] datos = porVia.get(edge);
            if (datos == null) return 0;
            avanzar(datos, reloj);
            long suma = 0;
            for (int i = 0; i < CUBETAS; i++) suma += datos[i];
            return (int) suma;
        }

        boolean saturada(PieroGEdge edge, double reloj) {
            return !porVia.isEmpty() && vehiculosPorHora(edge, reloj) > edge.flujoMaximo;
        }

        /** Pone a cero las cubetas que ya salieron de la ventana de una hora. */
        private static void avanzar(long[] datos, double reloj) {
            long actual = (long) (reloj / SEGUNDOS_CUBETA);
            long ultima = datos[CUBETAS];
            if (actual <= ultima) return;
            if (actual - ultima >= CUBETAS) {
                java.util.Arrays.fill(datos, 0, CUBETAS, 0);
            } else {
                for (long c = ultima + 1; c <= actual; c++) datos[(int) (c % CUBETAS)] = 0;
            }
            datos[CUBETAS] = actual;
        }
    }

    /** POIs que pueden ser destino de los pasajeros; rige para los pasajeros que suban después. */
    public void setPuntosInteres(List<EberthMPoi> pois) {
        demandaParadas.setPois(new EberthMIndicePoi(List.copyOf(pois)));
        registrarUbicacionesFisicas(pois);
    }

    /** La gente aparece junto a cada POI (nodo de calle más cercano), haya o no una ruta allí. */
    private void registrarUbicacionesFisicas(List<EberthMPoi> pois) {
        ubicacionesPoi = pois;
        if (grafo == null) return;
        Map<Integer, PieroGNodo> nodos = new HashMap<>();
        for (EberthMPoi poi : pois) {
            PieroGNodo nodo = grafo.nodoMasCercanoLonLat(poi.lon(), poi.lat());
            if (nodo != null) nodos.putIfAbsent(nodo.id, nodo);
        }
        demandaParadas.setUbicacionesFisicas(new ArrayList<>(nodos.values()));
        registrarOrigenesResidenciales();
    }

    /** Máximo de puntos de origen residencial: la demanda se reparte entre ellos, no crece. */
    private static final int MAX_ORIGENES_RESIDENCIALES = 400;

    /**
     * Origen residencial: la gente también sale de las calles {@code residential}. Se toma un
     * nodo cada cierto número de tramos residenciales (muestreo uniforme y determinista).
     */
    private void registrarOrigenesResidenciales() {
        List<PieroGNodo> residenciales = new ArrayList<>();
        for (PieroGEdge e : grafo.getEdges()) {
            if ("residential".equals(e.highway)) residenciales.add(e.origen);
        }
        int paso = Math.max(1, residenciales.size() / MAX_ORIGENES_RESIDENCIALES);
        Map<Integer, PieroGNodo> elegidos = new HashMap<>();
        for (int i = 0; i < residenciales.size(); i += paso) {
            elegidos.putIfAbsent(residenciales.get(i).id, residenciales.get(i));
        }
        demandaParadas.setOrigenesResidenciales(new ArrayList<>(elegidos.values()));
    }

    private List<EberthMPoi> ubicacionesPoi = List.of();

    /**
     * Ejecutor donde se calculan los desvíos A*. Por defecto se calculan en línea (determinista,
     * para pruebas); el motor de simulación entrega un pool de fondo para que el recálculo no
     * detenga el paso de simulación: el micro espera y retoma la ruta cuando el cálculo termina.
     */
    public void setEjecutorDesvios(Executor ejecutor) {
        presupuestoDesvios.ejecutor = Objects.requireNonNull(ejecutor);
    }

    /** Usa la demanda de la ciudad (zonas de origen y curva horaria) como fuente de pasajeros; null la quita. */
    public void setDemandaCiudad(EberthMDemandaCiudad demanda) {
        this.demandaCiudad = demanda;
    }

    public void configurarGrafo(PieroGGrafoVial grafo) {
        this.grafo = Objects.requireNonNull(grafo);
        this.pathfinder = new PieroGPathfinder(grafo);
        registrarUbicacionesFisicas(ubicacionesPoi);
        this.pathfinder.setModo(PieroGPathfinder.Modo.A_ESTRELLA);
        generadorEventos.setVias(grafo.getEdges());
        aplicarEscalaSemaforos();
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
                demandaParadas.registrarParadasRuta(despacho.paradasIda);
                demandaParadas.registrarParadasRuta(despacho.paradasVuelta);
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
        presupuestoDesvios.pendientes.clear();
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
        presupuestoDesvios.restantes = MAX_BUSQUEDAS_DESVIO_POR_PASO;
        for (Vehiculo vehiculo : vehiculos) {
            try {
                vehiculo.avanzar(delta, pathfinder, gasolineras, versionRed, desviosCache,
                        presupuestoDesvios, demandaParadas, segundosTranscurridos, flujoVias);
            } catch (RuntimeException ex) {
                vehiculo.estado = EberthMPosicionVehiculo.Estado.AVERIADO;
                LOG.log(Level.SEVERE, "Se aisló un error en el vehículo " + vehiculo.idRuta, ex);
            }
        }
        procesarDespachos();
        generarEventosOrganicos();
        demandaParadas.caminar(delta);
        demandaParadas.setMultiplicadorHorario(multiplicadorDemandaHorario());
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
    /** Minutos simulados promedio que esperaron en la parada los pasajeros que ya subieron. */
    public double esperaPromedioMinutos() {
        return demandaParadas.esperaPromedioMinutos();
    }

    /** Pasajeros que se cansaron de esperar y se fueron (taxi o a pie). */
    public int pasajerosAbandonaron() {
        return demandaParadas.abandonos();
    }

    /** Pasajeros nacidos en zonas con servicio desde que arrancó la simulación. */
    public long pasajerosGenerados() {
        return demandaParadas.generados();
    }

    /** Pasajeros que llegaron a su destino y bajaron del micro. */
    public int pasajerosBajaron() {
        return demandaParadas.bajaron;
    }

    /** Pasajeros que caminan hacia la parada de ruta más cercana. */
    public int pasajerosCaminando() {
        return demandaParadas.caminando();
    }

    /** Posiciones {@code x0, y0, x1, y1, …} de los grupos que caminan hacia una parada. */
    public double[] posicionesCaminantes() {
        return demandaParadas.posicionesCaminantes();
    }

    /** Ubicaciones donde se generó demanda pero ninguna parada de ruta queda a ≤ 300 m. */
    public int zonasSinCobertura() {
        return demandaParadas.zonasSinCobertura();
    }

    /** Pasajeros que no pudieron salir por falta de cobertura. */
    public int pasajerosSinCobertura() {
        return (int) Math.min(Integer.MAX_VALUE, demandaParadas.pasajerosSinCobertura());
    }

    /** Pasajeros que han hecho transbordo (bajaron lejos de su destino y buscan otro micro). */
    public int transbordos() {
        return demandaParadas.transbordos;
    }

    /** {@code x, y, pasajeros} de cada zona sin cobertura (mayor demanda primero). */
    public double[] zonasSinCoberturaXY() {
        return demandaParadas.zonasSinCoberturaXY();
    }

    /** {@code lon, lat, pasajeros} de cada zona sin cobertura, de mayor a menor demanda. */
    public double[] zonasSinCoberturaLonLat() {
        return demandaParadas.zonasSinCoberturaLonLat();
    }

    public String peorZonaSinCobertura() {
        return demandaParadas.nombrePeorZona();
    }

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
                        segundosTranscurridos, multiplicadorDemandaActual(), esHoraPuntaDemanda(),
                        desdeFinal -> rutaSinSaturar(desdeFinal
                                ? servicio.trayectoVuelta().edges() : servicio.trayecto().edges())))
                        .isPresent()) {
                    EberthMDespachador.Salida salida = pendiente.get();
                    // Los pasajeros salen de la cola real de la terminal, no de un contador propio
                    // del despachador: así una unidad llena no bloquea el abordaje en ruta.
                    int abordados = 0;
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
                    vehiculo.abordarEnTerminal(demandaParadas);
                    vehiculos.add(vehiculo);
                    if (!vehiculo.ruta.isEmpty()) {
                        flujoVias.registrarEntrada(vehiculo.ruta.getFirst(), segundosTranscurridos);
                    }
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
        if (grafo != null) grafo.setHoraPuntaSemaforos(esHoraPuntaDemanda());
    }

    /** Multiplicador por hora de la demanda de pasajeros; -1 sin parámetros de ciudad (perfil clásico). */
    private double multiplicadorDemandaHorario() {
        return parametrosCiudad == null ? -1 : multiplicadorDemandaActual();
    }

    /** Multiplicador de demanda del reloj actual: lo comparten las paradas y el despachador. */
    private double multiplicadorDemandaActual() {
        return EberthMPerfilHoraPunta.multiplicadorDemanda(parametrosCiudad, horaActualMinutos(),
                tipoDia, horaPuntaForzada);
    }

    /** Hora punta según los rangos configurados de la ciudad o forzada por el usuario. */
    public boolean esHoraPuntaDemanda() {
        return horaPuntaForzada || (parametrosCiudad != null
                && parametrosCiudad.esHoraPunta(horaActualMinutos(), tipoDia));
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
        return tipo == EberthMPosicionVehiculo.TipoVehiculo.BUS ? 50 : EberthMPosicionVehiculo.CAPACIDAD_MICRO;
    }

    public double getVelocidadSimulacion() {
        return velocidadSimulacion;
    }

    /**
     * Multiplica la demanda base de todas las rutas y el flujo que llega a cada parada. También
     * rige en una simulación en marcha: la generación de las paradas la lee en cada paso.
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
        aplicarEscalaSemaforos();
    }

    /** A x30 o más los semáforos duran el doble en el reloj simulado para que se vean cambiar. */
    private void aplicarEscalaSemaforos() {
        if (grafo == null) return;
        grafo.setEscalaTiempoSemaforos(velocidadSimulacion >= EberthMSemaforo.VELOCIDAD_CICLO_LARGO
                ? EberthMSemaforo.ESCALA_CICLO_LARGO : 1.0);
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
        /** Parte de {@link #destinosPoi} que subió porque el micro solo los acerca: harán transbordo. */
        private final Map<EberthMPoi, Integer> acercados = new HashMap<>();
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
        /** Hay un desvío calculándose en segundo plano para este vehículo. */
        private boolean calculandoDesvio;
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
                             PresupuestoDesvios presupuesto,
                             DemandaParadas demanda, double reloj, FlujoVias flujo) {
            // Sin ruta posible solo se reintenta al cambiar la red o por el temporizador de abajo:
            // buscar cada paso un desvío que no existe (A* sobre todo el mapa) congelaba la simulación.
            if (versionRuta != versionRed
                    || (estado != EberthMPosicionVehiculo.Estado.SIN_RUTA && hayViaCerradaAdelante())) {
                recalcularSiCerrada(pathfinder, versionRed, desviosCache, presupuesto);
            }
            if (estado == EberthMPosicionVehiculo.Estado.SIN_RUTA) {
                // Nunca queda errante: reintenta mientras no exista desvío.
                reintentoSinRuta -= segundos;
                if (calculandoDesvio || reintentoSinRuta <= 0) {
                    reintentoSinRuta = REINTENTO_SIN_RUTA_SEGUNDOS;
                    recalcularSiCerrada(pathfinder, versionRed, desviosCache, presupuesto);
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
                    recalcularSiCerrada(pathfinder, versionRed, desviosCache, presupuesto);
                    if (estado == EberthMPosicionVehiculo.Estado.SIN_RUTA) break;
                    continue;
                }
                // Un policía en el cruce de destino ordena el paso: la congestión pesa la mitad y
                // la vía no se trata como saturada.
                boolean conPolicia = edge.destino.policiaTransito;
                double congestion = Math.max(1.0, edge.factorTrafico * edge.factorHoraPunta);
                double velocidad = edge.velocidadLibreMps() / (conPolicia ? Math.max(1.0, congestion / 2) : congestion);
                if (!eventos.isEmpty() && bajoLluvia(edge, eventos)) {
                    velocidad *= EberthMTipoEvento.LLUVIA.multiplicadorVelocidad();
                }
                if (!conPolicia && flujo.saturada(edge, reloj)) velocidad *= FACTOR_VELOCIDAD_VIA_SATURADA;
                velocidad = Math.max(1.0, velocidad);
                double disponible = Math.max(0, edge.longitudMetros() - progresoMetros);
                double libre = disponible;
                EberthMSemaforo semaforo = edge.destino.semaforo;
                if ((edge.destino.semaforoRoto && !conPolicia) || (semaforo != null && semaforo.esRojo(reloj,
                        EberthMSemaforo.Eje.de(edge), edge.destino.policiaTransito))) {
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
                if (recorrido > 0) demanda.bajaron += bajarEnPois(edge, progresoPrevio, progresoMetros, demanda);
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
                    if (indiceEdge < ruta.size()) flujo.registrarEntrada(ruta.get(indiceEdge), reloj);
                    if (indiceEdge >= ruta.size()) {
                        demanda.bajaron += pasajeros;
                        pasajeros = 0; // en el terminal bajan todos
                        destinos.clear();
                        destinosPoi.clear();
                        puntosBajada.clear();
                        acercados.clear();
                        ruta = rutaAlternativa;
                        enIda = !enIda;
                        paradas = enIda ? paradasIda : paradasVuelta;
                        siguienteParada = 1;
                        abordajePendiente = !paradas.isEmpty();
                        versionRuta = -1; // la nueva ruta se revisa por completo en el próximo paso
                        indiceEdge = 0;
                        if (!ruta.isEmpty()) flujo.registrarEntrada(ruta.getFirst(), reloj);
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
            subirPasajeros(paradas.getFirst(), demanda);
        }

        /** En una parada primero bajan los que van allí y luego suben los que esperan. */
        private void intercambiarPasajeros(PieroGNodo parada, DemandaParadas demanda) {
            Integer bajan = destinos.remove(parada);
            if (bajan != null) {
                pasajeros -= bajan;
                demanda.bajaron += bajan;
            }
            int futuras = paradas.size() - siguienteParada;
            if (futuras <= 0) return; // última parada: nadie sube, no tendría destino
            subirPasajeros(parada, demanda);
        }

        /**
         * Abordaje universal: sube a quien espere en la ubicación y cuyo POI destino quede a
         * ≤ 400 m de lo que falta de ruta, sin importar qué ruta tenía pensada. Los pasajeros sin
         * POI (ciudad sin puntos de interés) suben y se reparten entre las paradas restantes.
         */
        private void subirPasajeros(PieroGNodo parada, DemandaParadas demanda) {
            int libres = (int) (capacidad * FACTOR_SOBRECUPO) - pasajeros;
            if (libres <= 0) return;
            List<EberthMIndicePoi.Candidato> adelante = candidatosAdelante(demanda.pois());
            Set<EberthMPoi> alcanzables = Collections.newSetFromMap(new IdentityHashMap<>());
            for (EberthMIndicePoi.Candidato c : adelante) alcanzables.add(c.poi());
            double[] pos = ruta.isEmpty() ? null : posicionActual();
            // Abordaje universal: quien espera sube al primer micro con espacio que pase por su parada;
            // si no lo lleva a su destino lo deja en el punto de la ruta más cercano y hace transbordo.
            Map<EberthMPoi, Boolean> acercaTransbordo = new HashMap<>();
            int[] sinPoi = {0};
            int suben = demanda.abordar(parada, libres, (poi, transbordo) -> {
                if (alcanzables.contains(poi)) return true;
                if (pos == null) return false;
                // Quien ya hizo un transbordo solo sube a un micro que lo deje en su destino o lo acerque.
                return !transbordo || acercaTransbordo.computeIfAbsent(poi, p -> acerca(p, pos));
            }, (poi, n) -> {
                if (poi == null) {
                    sinPoi[0] += n;
                } else if (EberthMIndicePoi.distanciaPuntoSegmento(poi.lon(), poi.lat(),
                        pos[0], pos[1], pos[0], pos[1]) > EberthMIndicePoi.RADIO_BAJADA_METROS) {
                    destinosPoi.merge(poi, n, Integer::sum);
                    if (!alcanzables.contains(poi)) acercados.merge(poi, n, Integer::sum);
                    puntosBajada.computeIfAbsent(poi, this::puntoMasCercanoEnRuta);
                } else {
                    sinPoi[0] += n; // destino pegado al punto de subida: bajaría de inmediato
                }
            });
            pasajeros += suben;
            asignarDestinosAParadas(sinPoi[0], demanda);
        }

        /** Reparte pasajeros sin POI destino entre las paradas que aún le faltan al vehículo. */
        private void asignarDestinosAParadas(int cantidad, DemandaParadas demanda) {
            int futuras = paradas.size() - siguienteParada;
            if (cantidad <= 0) return;
            if (futuras <= 0) { // sin dónde bajar: no suben
                pasajeros -= cantidad;
                demanda.deshacerAbordaje(cantidad);
                return;
            }
            for (int i = 0; i < cantidad; i++) {
                destinos.merge(paradas.get(siguienteParada + demanda.sorteo(futuras)), 1, Integer::sum);
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
        private record PuntoBajada(PieroGEdge edge, double metros, double distancia) {}

        /** El micro deja a quien ya hizo un transbordo al menos {@value #ACERCAMIENTO_TRANSBORDO_METROS} m más cerca de su POI. */
        private boolean acerca(EberthMPoi poi, double[] pos) {
            PuntoBajada mejor = puntoMasCercanoEnRuta(poi);
            if (mejor == null) return false;
            double actual = EberthMIndicePoi.distanciaPuntoSegmento(poi.lon(), poi.lat(),
                    pos[0], pos[1], pos[0], pos[1]);
            return actual - mejor.distancia() >= ACERCAMIENTO_TRANSBORDO_METROS;
        }

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
                    mejor = new PuntoBajada(e, (desde + p[1] * (1 - desde)) * largo, p[0]);
                }
            }
            return mejor;
        }

        /**
         * Baja a los pasajeros cuyo POI destino queda a menos de 50 m del trayecto recorrido en
         * este paso (segmento entre ambos progresos, para no saltarse un POI a alta velocidad) o
         * cuyo punto de la ruta más cercano ({@link #puntosBajada}) se cruzó en este paso.
         */
        private int bajarEnPois(PieroGEdge edge, double desdeMetros, double hastaMetros,
                            DemandaParadas demanda) {
            if (destinosPoi.isEmpty()) return 0;
            int bajados = 0;
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
                // A menos de 400 m del POI destino el pasajero baja y camina el resto: «llegó».
                if (cruzado || EberthMIndicePoi.distanciaPuntoSegmento(poi.lon(), poi.lat(), aLon, aLat,
                        bLon, bLat) <= EberthMIndicePoi.RADIO_CAMINATA_METROS) {
                    int n = e.getValue();
                    pasajeros -= n;
                    bajados += n;
                    it.remove();
                    puntosBajada.remove(poi);
                    Integer parciales = acercados.remove(poi);
                    if (parciales != null && parciales > 0
                            && EberthMIndicePoi.distanciaPuntoSegmento(poi.lon(), poi.lat(), bLon, bLat,
                            bLon, bLat) > RADIO_TRANSBORDO_METROS) {
                        // Bajan lejos de su destino: buscan un segundo micro desde el nodo más cercano.
                        bajados -= parciales;
                        PieroGNodo lugar = f1 >= 0.5 ? edge.destino : edge.origen;
                        demanda.transbordar(lugar, poi, parciales);
                    }
                }
            }
            return bajados;
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
                                         Map<RutaDesvioKey, List<PieroGEdge>> desviosCache,
                                         PresupuestoDesvios presupuesto) {
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
                calculandoDesvio = false;
                if (!desviarHastaSiguienteParada(pathfinder, bloqueada, desviosCache, presupuesto)) {
                    if (calculandoDesvio && bloqueada > indiceEdge) {
                        // Aún no llega al bloqueo: sigue avanzando mientras el A* termina en segundo plano.
                        versionRuta = -1;
                        return;
                    }
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
                                                    Map<RutaDesvioKey, List<PieroGEdge>> desviosCache,
                                                    PresupuestoDesvios presupuesto) {
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
            if (desvio != null && desvio.isEmpty()) return false; // sin desvío posible (caché negativa)
            if (desvio == null) {
                CompletableFuture<List<PieroGEdge>> futuro = presupuesto.pendientes.get(clave);
                if (futuro == null) {
                    // Sin presupuesto el vehículo espera (SIN_RUTA) y reintenta en el paso siguiente.
                    if (presupuesto.restantes <= 0) return false;
                    presupuesto.restantes--;
                    List<PieroGEdge> referencia = List.copyOf(ruta.subList(prefijo + 1, reenganche + 1));
                    PieroGNodo ini = inicio;
                    futuro = CompletableFuture.supplyAsync(() -> buscarDesvio(pathfinder, ini, objetivo, referencia),
                            presupuesto.ejecutor);
                    if (!futuro.isDone()) presupuesto.pendientes.put(clave, futuro);
                }
                if (!futuro.isDone()) {
                    calculandoDesvio = true;
                    return false;
                }
                presupuesto.pendientes.remove(clave);
                desvio = futuro.join();
                if (desviosCache.size() >= MAX_DESVIOS_CACHE) desviosCache.clear();
                // Un desvío vacío recuerda el fallo hasta que cambie la red (invalidarRutas limpia la caché).
                desviosCache.put(clave, desvio);
                if (desvio.isEmpty()) return false;
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

        /** A* cerca de la ruta original (≈ 2 cuadras); solo si no hay paso se amplía. Vacío = sin desvío. */
        private static List<PieroGEdge> buscarDesvio(PieroGPathfinder pathfinder, PieroGNodo inicio,
                                                     PieroGNodo objetivo, List<PieroGEdge> referencia) {
            try {
                PieroGRutaResult resultado = null;
                for (double radio : RADIOS_DESVIO_METROS) {
                    resultado = pathfinder.rutaCercana(inicio, objetivo, referencia, radio);
                    if (resultado != null) break;
                }
                if (resultado == null) resultado = pathfinder.ruta(inicio, objetivo);
                if (resultado == null || resultado.getEdges().isEmpty()) return List.of();
                return List.copyOf(resultado.getEdges());
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "Falló el cálculo de desvío", ex);
                return List.of();
            }
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

    /** Búsquedas de desvío que aún se pueden hacer en el paso actual. */
    private static final class PresupuestoDesvios {
        private int restantes;
        private Executor ejecutor = Runnable::run;
        /** Desvíos en cálculo; solo se lee/escribe desde el hilo de simulación. */
        private final Map<RutaDesvioKey, CompletableFuture<List<PieroGEdge>>> pendientes = new HashMap<>();
    }

    private static final class DespachoProgramado {
        private final EberthMDespachador despachador;
        private List<PieroGNodo> paradasIda;
        private List<PieroGNodo> paradasVuelta;

        private DespachoProgramado(EberthMServicioMicro servicio, long demandaBase, int escalaDemanda) {
            this.despachador = new EberthMDespachador(servicio, demandaBase, escalaDemanda);
        }
    }

    /**
     * Demanda agregada: un contador entero de pasajeros esperando por parada
     * (clave: id del nodo), sin simular personas individuales.
     */
    private static final class DemandaParadas {
        private static final int PASAJEROS_POR_MINUTO = 5;
        /**
         * Cuántos habitantes que usan transporte público representa cada pasajero simulado
         * («factor de escala»). Mayor valor = menos gente en pantalla.
         */
        private static final double FACTOR_ESCALA_POBLACION =
                EberthMParametrosCiudad.ESCALA_POBLACION_SIMULADA;
        private static final int MINUTOS_POR_DIA = 24 * 60;
        /** Sin multiplicador horario configurado (sin parámetros de ciudad) rige el perfil clásico. */
        private double multiplicadorHorario = -1;

        void setMultiplicadorHorario(double multiplicador) {
            this.multiplicadorHorario = multiplicador;
        }

        double multiplicadorHorario() {
            return multiplicadorHorario;
        }

        /** Pasajeros por minuto de toda la ciudad: población × % de uso ÷ factor de escala, repartida en el día. */
        double demandaCiudadPorMinuto() {
            if (perfilCiudad == null) return 0;
            return perfilCiudad.usuariosTransporteDiarios()
                    / perfilCiudad.escalaPoblacionSimulada() / MINUTOS_POR_DIA;
        }

        /** Grupos con destino distinto en que se divide la llegada de un minuto a una ubicación. */
        private static final int GRUPOS_POR_MINUTO = 4;
        /** Minutos simulados tras los que un pasajero se rinde (toma un taxi o camina). */
        private static final int PACIENCIA_MINUTOS = 15;
        /**
         * Tope del factor de demanda (perfil horario × día × demografía). Sin él, categorías con
         * picos de 2,5 y demografías extremas multiplicaban la llegada de gente por más de 10.
         */
        private static final double FACTOR_DEMANDA_MAXIMO = 3.0;
        private int maximoPorParada = EberthMParametrosCiudad.CAPACIDAD_PARADA_POR_DEFECTO;
        private final Map<Integer, ColaEspera> esperando = new HashMap<>();
        private final Map<Integer, PieroGNodo> nodos = new HashMap<>();
        private int ultimoMinuto;
        private int abordadosSinContar;
        private long esperaTotal;
        private long abordadosConEspera;
        private int abandonos;
        /** Pasajeros que llegaron a su destino y bajaron (lo suma el vehículo al bajarlos). */
        int bajaron;
        /** Pasajeros nacidos en zonas con servicio (en cola, caminando, rendidos, a bordo o ya llegados). */
        private long generados;
        /** Velocidad a pie: 5 km/h. */
        private static final double VELOCIDAD_CAMINATA_MPS = 5 / 3.6;
        /** Más lejos que esto nadie camina hasta una parada: la zona queda «sin cobertura». */
        private static final double CAMINATA_MAXIMA_METROS = 300;
        /** Zonas sin cobertura que se retienen para el mapa y la alerta (las de mayor demanda). */
        private static final int MAX_ZONAS_SIN_COBERTURA = 200;
        /** Pasajeros no atendidos por ubicación sin parada de ruta a ≤ 300 m (clave: id del nodo). */
        private final Map<Integer, Integer> sinCobertura = new HashMap<>();
        private long pasajerosSinCobertura;
        /** Pasajeros que bajaron lejos de su destino y buscan un segundo micro. */
        int transbordos;
        /** Paradas de las rutas activas: allí esperan los micros; fuera de ellas se camina. */
        private final Map<Integer, PieroGNodo> paradasRuta = new HashMap<>();
        /** Parada de ruta más cercana de cada ubicación (null = ninguna a distancia de caminata). */
        private final Map<Integer, Destino> caminataCache = new HashMap<>();
        private final List<Caminante> caminantes = new ArrayList<>();
        private int caminando;
        private final java.util.Random azar = new java.util.Random();
        /** Fracción de la demanda diaria que nace en cada nodo-zona (clave: id del nodo); null = reparto uniforme. */
        private Map<Integer, Double> compartirZona;
        private List<PieroGNodo> nodosZona = List.of();
        /** Pasajeros simulados por hora del día (viajes ÷ factor de escala). */
        private double[] pasajerosHoraSimulados;
        private EberthMDemandaCiudad demandaZonas;
        /** Zonas de la demanda cuyo centro cae en cada nodo (clave: id del nodo). */
        private Map<Integer, List<Integer>> zonasDeNodo = new HashMap<>();

        void setDemandaZonas(EberthMDemandaCiudad demanda, PieroGGrafoVial red) {
            if (demanda == null || red == null) {
                compartirZona = null;
                nodosZona = List.of();
                pasajerosHoraSimulados = null;
                demandaZonas = null;
                zonasDeNodo = new HashMap<>();
                return;
            }
            double total = 0;
            for (EberthMDemandaCiudad.Zona z : demanda.zonas()) total += demanda.viajesDesdeZona(z.id());
            Map<Integer, Double> reparto = new HashMap<>();
            Map<Integer, PieroGNodo> unicos = new HashMap<>();
            Map<Integer, List<Integer>> porNodo = new HashMap<>();
            for (EberthMDemandaCiudad.Zona z : demanda.zonas()) {
                PieroGNodo nodo = red.nodoMasCercanoLonLat(z.lon(), z.lat());
                if (nodo == null || total <= 0) continue;
                reparto.merge(nodo.id, demanda.viajesDesdeZona(z.id()) / total, Double::sum);
                porNodo.computeIfAbsent(nodo.id, k -> new ArrayList<>()).add(z.id());
                unicos.put(nodo.id, nodo);
            }
            compartirZona = reparto;
            demandaZonas = demanda;
            zonasDeNodo = porNodo;
            nodosZona = new ArrayList<>(unicos.values());
            pasajerosHoraSimulados = demanda.viajesPorHora();
            double escala = perfilCiudad == null ? FACTOR_ESCALA_POBLACION : perfilCiudad.escalaPoblacionSimulada();
            for (int h = 0; h < 24; h++) pasajerosHoraSimulados[h] /= escala;
        }

        /** Destino según el flujo zona → POI de la zona donde nace el pasajero; null si no hay flujo. */
        private EberthMPoi destinoPorFlujo(int idNodo) {
            List<Integer> zonas = zonasDeNodo.get(idNodo);
            if (demandaZonas == null || zonas == null || zonas.isEmpty()) return null;
            int zona = zonas.get(azar.nextInt(zonas.size()));
            return EberthMGeneradorDemanda.sortearDestinoPorFlujo(demandaZonas, zona, azar);
        }

        void reiniciar() {
            esperando.clear();
            nodos.clear();
            ultimoMinuto = 0;
            abordadosSinContar = 0;
            esperaTotal = 0;
            abordadosConEspera = 0;
            abandonos = 0;
            bajaron = 0;
            generados = 0;
            paradasRuta.clear();
            caminataCache.clear();
            caminantes.clear();
            caminando = 0;
            sinCobertura.clear();
            pasajerosSinCobertura = 0;
            transbordos = 0;
            registrar(ubicacionesFisicas);
            registrar(new ArrayList<>(residenciales.values()));
            registrar(nodosZona);
        }

        void setMaximoPorParada(int maximo) {
            this.maximoPorParada = Math.max(1, maximo);
        }

        void registrar(List<PieroGNodo> paradas) {
            for (PieroGNodo nodo : paradas) {
                nodos.putIfAbsent(nodo.id, nodo);
                esperando.putIfAbsent(nodo.id, new ColaEspera());
            }
        }

        /** Marca las paradas como atendidas por una ruta: allí la gente espera el micro. */
        void registrarParadasRuta(List<PieroGNodo> paradas) {
            registrar(paradas);
            boolean nuevas = false;
            for (PieroGNodo nodo : paradas) nuevas |= paradasRuta.putIfAbsent(nodo.id, nodo) == null;
            if (nuevas) {
                caminataCache.clear();
                sinCobertura.keySet().removeIf(id -> {
                    PieroGNodo zona = nodos.get(id);
                    return zona != null && paradaMasCercana(zona) != null;
                });
            }
        }

        private record Destino(PieroGNodo parada, double metros) {}

        /** Grupo que camina de su ubicación a una parada; al llegar se une a la cola de espera. */
        private static final class Caminante {
            final PieroGNodo origen;
            final Destino destino;
            final EberthMPoi poi;
            final int cantidad;
            final boolean transbordo;
            double recorrido;

            Caminante(PieroGNodo origen, Destino destino, EberthMPoi poi, int cantidad,
                      boolean transbordo) {
                this.origen = origen;
                this.destino = destino;
                this.poi = poi;
                this.cantidad = cantidad;
                this.transbordo = transbordo;
            }
        }

        private Destino paradaMasCercana(PieroGNodo origen) {
            return caminataCache.computeIfAbsent(origen.id, id -> {
                Destino mejor = null;
                for (PieroGNodo parada : paradasRuta.values()) {
                    double d = distanciaMetros(origen, parada);
                    if (d <= CAMINATA_MAXIMA_METROS && (mejor == null || d < mejor.metros)) {
                        mejor = new Destino(parada, d);
                    }
                }
                return mejor;
            });
        }

        private static double distanciaMetros(PieroGNodo a, PieroGNodo b) {
            return EberthMIndicePoi.distanciaPuntoSegmento(a.lon, a.lat, b.lon, b.lat, b.lon, b.lat);
        }

        int caminando() {
            return caminando;
        }

        /** Avanza a los caminantes {@code segundos}; quien llega se une a la espera de su parada. */
        void caminar(double segundos) {
            if (caminantes.isEmpty()) return;
            double metros = segundos * VELOCIDAD_CAMINATA_MPS;
            var it = caminantes.iterator();
            while (it.hasNext()) {
                Caminante c = it.next();
                c.recorrido += metros;
                if (c.recorrido < c.destino.metros) continue;
                it.remove();
                caminando -= c.cantidad;
                ColaEspera cola = esperando.get(c.destino.parada.id);
                int admitidos = cola == null ? 0
                        : cola.llegar(ultimoMinuto, c.cantidad, maximoPorParada, c.poi, c.transbordo);
                abandonos += c.cantidad - admitidos; // parada llena: no cabe y se va
            }
        }

        /**
         * Posiciones {@code x, y, transbordo} de cada grupo que camina, interpoladas entre su
         * origen y su parada ({@code transbordo} = 1 si va a su segundo micro).
         */
        double[] posicionesCaminantes() {
            double[] xy = new double[caminantes.size() * 3];
            int i = 0;
            for (Caminante c : caminantes) {
                double f = Math.min(1, c.recorrido / Math.max(1e-6, c.destino.metros));
                xy[i++] = c.origen.x + (c.destino.parada.x - c.origen.x) * f;
                xy[i++] = c.origen.y + (c.destino.parada.y - c.origen.y) * f;
                xy[i++] = c.transbordo ? 1 : 0;
            }
            return xy;
        }

        /** Pasajeros que llegaron juntos a una ubicación con el mismo destino (null = sin POI). */
        private static final class Grupo {
            final int minuto;
            final EberthMPoi destino;
            /** Viene de bajar de otro micro: solo sube a uno que lo lleve directo a su destino. */
            final boolean transbordo;
            int cantidad;

            Grupo(int minuto, EberthMPoi destino, int cantidad, boolean transbordo) {
                this.minuto = minuto;
                this.destino = destino;
                this.cantidad = cantidad;
                this.transbordo = transbordo;
            }
        }

        /** Grupos de una ubicación física en orden de llegada; los más antiguos abordan primero. */
        private static final class ColaEspera {
            private final java.util.LinkedList<Grupo> grupos = new java.util.LinkedList<>();
            private int total;
            /** Pasajeros-minuto que llevan esperando los que ya subieron (para el promedio). */
            private long esperaSuma;
            /** Pasajeros de transbordo que subieron: ya se contaron al subir al primer micro. */
            private long reabordados;

            /** Agrega los pasajeros llegados en {@code minuto} que caben hasta {@code capacidad}. */
            int llegar(int minuto, long cantidad, int capacidad, EberthMPoi destino,
                       boolean transbordo) {
                int admitidos = (int) Math.min(Math.max(0, cantidad), Math.max(0, capacidad - total));
                if (admitidos == 0) return 0;
                Grupo ultimo = grupos.peekLast();
                if (ultimo != null && ultimo.minuto == minuto && ultimo.destino == destino
                        && ultimo.transbordo == transbordo) {
                    ultimo.cantidad += admitidos;
                } else {
                    grupos.addLast(new Grupo(minuto, destino, admitidos, transbordo));
                }
                total += admitidos;
                return admitidos;
            }

            /** Pasajeros en espera que vienen de un transbordo. */
            int enTransbordo() {
                int n = 0;
                for (Grupo g : grupos) if (g.transbordo) n += g.cantidad;
                return n;
            }

            /** Quita a quienes llevan esperando {@code paciencia} minutos o más. */
            int rendirse(int minutoActual, int paciencia) {
                int rendidos = 0;
                for (Grupo g = grupos.peekFirst(); g != null && minutoActual - g.minuto >= paciencia;
                     g = grupos.peekFirst()) {
                    total -= g.cantidad;
                    rendidos += g.cantidad;
                    grupos.removeFirst();
                }
                return rendidos;
            }

            /**
             * Sube hasta {@code maximo} pasajeros cuyo destino sirve el vehículo (o sin destino);
             * los demás siguen esperando otro micro.
             */
            int subir(int maximo, int minutoActual,
                      java.util.function.BiPredicate<EberthMPoi, Boolean> sirve,
                      java.util.function.ObjIntConsumer<EberthMPoi> alSubir) {
                int suben = 0;
                var it = grupos.iterator();
                while (suben < maximo && it.hasNext()) {
                    Grupo g = it.next();
                    if (g.destino != null && !sirve.test(g.destino, g.transbordo)) continue;
                    int toma = Math.min(maximo - suben, g.cantidad);
                    g.cantidad -= toma;
                    suben += toma;
                    esperaSuma += (long) toma * Math.max(0, minutoActual - g.minuto);
                    if (g.transbordo) reabordados += toma;
                    alSubir.accept(g.destino, toma);
                    if (g.cantidad == 0) it.remove();
                }
                total -= suben;
                return suben;
            }
        }

        /** Radio alrededor de la parada en que un POI influye en su demanda (≈ 2 cuadras). */
        private static final double RADIO_INFLUENCIA_POI_METROS = 250;
        private EberthMParametrosCiudad perfilCiudad;
        private EberthMTipoDia perfilDia = EberthMTipoDia.LABORAL;
        /** Cuántos POIs de cada categoría hay cerca de cada parada (caché; se vacía al cambiar POIs). */
        private final Map<Integer, int[]> poisCercanos = new HashMap<>();

        /** Fracción de los usuarios diarios que viaja en cada minuto, según las horas punta configuradas. */
        private double[] perfilMinuto;

        void setPerfil(EberthMParametrosCiudad ciudad, EberthMTipoDia dia) {
            this.perfilCiudad = ciudad;
            this.perfilDia = dia;
            this.perfilMinuto = ciudad == null ? null : ciudad.perfilPorMinuto(dia);
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
            boolean porParametros = perfilCiudad != null && multiplicadorHorario >= 0;
            final double general = porParametros ? 1
                    : EberthMPerfilDemanda.factorGeneral(minutoDelDia, perfilDia);
            // Con parámetros de ciudad la población, el % de uso y la hora punta fijan la cantidad;
            // las categorías solo reparten esa gente entre ubicaciones (media 1).
            double escalaHoraria = 1;
            if (porParametros) {
                double media = java.util.Arrays.stream(porCategoria).average().orElse(1);
                for (int i = 0; i < porCategoria.length; i++) porCategoria[i] /= Math.max(1e-9, media);
                // Densidad del minuto respecto a un reparto plano (1 = promedio): sale de las horas
                // punta y del % de población en punta configurados, no de franjas fijas.
                double densidad = perfilMinuto[Math.floorMod(minutoDelDia, MINUTOS_POR_DIA)];
                // Hora punta forzada por el usuario: rige la densidad del minuto más cargado.
                if (multiplicadorHorario >= EberthMPerfilHoraPunta.MULTIPLICADOR_HORA_PUNTA) {
                    densidad = Math.max(densidad, java.util.Arrays.stream(perfilMinuto).max().orElse(densidad));
                }
                escalaHoraria = densidad * MINUTOS_POR_DIA * perfilDia.demandaGlobal();
            }
            // Tope literal por parada: quien no cabe se rinde y camina a otra parada.
            int tope = maximoPorParada;
            double porUbicacion = porParametros
                    ? demandaCiudadPorMinuto() / Math.max(1, esperando.size()) : PASAJEROS_POR_MINUTO;
            boolean porZonas = compartirZona != null;
            double base = porZonas
                    ? minutos * pasajerosHoraSimulados[Math.floorMod(minutoDelDia, MINUTOS_POR_DIA) / 60] / 60.0 * escala
                    : (double) minutos * porUbicacion * escala * escalaHoraria;
            esperando.forEach((id, cola) -> {
                abandonos += cola.rendirse(minutoSimulado, PACIENCIA_MINUTOS);
                boolean servida = paradasRuta.containsKey(id);
                // Sin parada de ruta cerca nadie sale a esperar: no hay generación.
                Destino caminata = servida ? null : paradaMasCercana(nodos.get(id));
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
                // Calle residencial: gente solo en las horas de salida y regreso.
                if (!servida && residenciales.containsKey(id) && !fisicas.contains(id)) factor = horaResidencial(minutoDelDia) ? 2.0 : 0.1;
                factor = Math.min(factor, FACTOR_DEMANDA_MAXIMO);
                // Con demanda de ciudad la gente nace solo en sus zonas, en proporción a su demanda.
                if (porZonas) factor = compartirZona.getOrDefault(id, 0.0);
                // Vendedores ambulantes cerca de la parada: aglomeración (+200 %).
                double aglomeracion = eventos.isEmpty() ? 1.0
                        : EberthMAplicadorEventos.multiplicadorDemanda(nodos.get(id), eventos);
                // long: con escala alta y tiempo acelerado el producto no cabe en un int.
                double esperados = base * factor * aglomeracion;
                // Redondeo aleatorio: una ubicación con 0,3 pasajeros/min recibe uno cada ~3 min.
                long nuevos = (porParametros || porZonas)
                        ? (long) esperados + (azar.nextDouble() < esperados - Math.floor(esperados) ? 1 : 0)
                        : Math.round(esperados);
                if (!servida && caminata == null) {
                    // Ninguna parada de ruta a ≤ 300 m: la demanda existe pero no hay transporte.
                    if (nuevos > 0) registrarSinCobertura(id, nuevos);
                    return;
                }
                generados += nuevos;
                if (servida && cola.total >= tope) {
                    // Parada llena: la gente que llega se va. Antes se descartaba sin contarla.
                    abandonos += (int) Math.min(Integer.MAX_VALUE - abandonos, nuevos);
                    return;
                }
                // Cada tanda se reparte en pocos grupos, cada uno con su POI destino.
                int tandas = (int) Math.min(GRUPOS_POR_MINUTO, nuevos);
                for (int i = 0; i < tandas; i++) {
                    long parte = nuevos / tandas + (i < nuevos % tandas ? 1 : 0);
                    EberthMPoi destino = porZonas ? destinoPorFlujo(id) : null;
                    if (destino == null) destino = sortearDestino(nodos.get(id), minutoDelDia);
                    if (servida) {
                        int admitidos = cola.llegar(minutoSimulado, parte, tope, destino, false);
                        abandonos += (int) (parte - admitidos);
                    } else {
                        caminantes.add(new Caminante(nodos.get(id), caminata, destino, (int) parte, false));
                        caminando += (int) parte;
                    }
                }
            });
        }

        private void registrarSinCobertura(int idNodo, long cantidad) {
            pasajerosSinCobertura += cantidad;
            sinCobertura.merge(idNodo, (int) Math.min(Integer.MAX_VALUE, cantidad), Integer::sum);
            if (sinCobertura.size() > MAX_ZONAS_SIN_COBERTURA * 2) {
                // Se conservan solo las de mayor demanda para no crecer sin límite.
                var mayores = sinCobertura.entrySet().stream()
                        .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                        .limit(MAX_ZONAS_SIN_COBERTURA).toList();
                Map<Integer, Integer> copia = new HashMap<>();
                mayores.forEach(e -> copia.put(e.getKey(), e.getValue()));
                sinCobertura.clear();
                sinCobertura.putAll(copia);
            }
        }

        int zonasSinCobertura() {
            return sinCobertura.size();
        }

        long pasajerosSinCobertura() {
            return pasajerosSinCobertura;
        }

        /** {@code x, y, pasajeros} de cada zona sin cobertura, ordenadas de mayor a menor demanda. */
        double[] zonasSinCoberturaXY() {
            var orden = sinCobertura.entrySet().stream()
                    .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                    .limit(MAX_ZONAS_SIN_COBERTURA).toList();
            double[] xy = new double[orden.size() * 3];
            int i = 0;
            for (var e : orden) {
                PieroGNodo n = nodos.get(e.getKey());
                xy[i++] = n.x;
                xy[i++] = n.y;
                xy[i++] = e.getValue();
            }
            return xy;
        }

        double[] zonasSinCoberturaLonLat() {
            var orden = sinCobertura.entrySet().stream()
                    .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                    .limit(MAX_ZONAS_SIN_COBERTURA).toList();
            double[] datos = new double[orden.size() * 3];
            int i = 0;
            for (var e : orden) {
                PieroGNodo n = nodos.get(e.getKey());
                datos[i++] = n.lon;
                datos[i++] = n.lat;
                datos[i++] = e.getValue();
            }
            return datos;
        }

        /** Nombre del POI más cercano a la zona sin cobertura de mayor demanda (o coordenadas). */
        String nombrePeorZona() {
            var peor = sinCobertura.entrySet().stream().max(Map.Entry.comparingByValue());
            if (peor.isEmpty()) return "";
            PieroGNodo n = nodos.get(peor.get().getKey());
            EberthMPoi cercano = null;
            double mejor = Double.MAX_VALUE;
            for (EberthMPoi poi : pois.cercanos(n.lon, n.lat, EberthMIndicePoi.RADIO_CAMINATA_METROS)) {
                double d = distanciaMetros(n, poi.lon(), poi.lat());
                if (d < mejor) { mejor = d; cercano = poi; }
            }
            return cercano != null ? cercano.nombre()
                    : String.format("%.5f, %.5f", n.lat, n.lon);
        }

        private static double distanciaMetros(PieroGNodo a, double lon, double lat) {
            return EberthMIndicePoi.distanciaPuntoSegmento(a.lon, a.lat, lon, lat, lon, lat);
        }

        /**
         * Transbordo: {@code cantidad} pasajeros bajaron en {@code lugar} lejos de su destino.
         * Caminan a la parada de ruta más cercana (≤ 300 m) a buscar un segundo micro; si no hay
         * ninguna se rinden.
         */
        void transbordar(PieroGNodo lugar, EberthMPoi destino, int cantidad) {
            if (cantidad <= 0) return;
            Destino parada = paradaMasCercana(lugar);
            if (parada == null) {
                abandonos += cantidad;
                return;
            }
            transbordos += cantidad;
            caminantes.add(new Caminante(lugar, parada, destino, cantidad, true));
            caminando += cantidad;
        }

        /**
         * Sube hasta {@code libres} pasajeros de la ubicación cuyo destino sirve el vehículo,
         * sea cual sea su ruta; el resto sigue esperando.
         */
        int abordar(PieroGNodo parada, int libres,
                    java.util.function.BiPredicate<EberthMPoi, Boolean> sirve,
                    java.util.function.ObjIntConsumer<EberthMPoi> alSubir) {
            ColaEspera cola = esperando.get(parada.id);
            if (cola == null || libres <= 0) return 0;
            long esperaAntes = cola.esperaSuma;
            long reabordosAntes = cola.reabordados;
            int suben = cola.subir(libres, ultimoMinuto, sirve, alSubir);
            esperaTotal += cola.esperaSuma - esperaAntes;
            abordadosConEspera += suben;
            // «Subieron» cuenta personas distintas: quien hace transbordo no vuelve a contarse.
            abordadosSinContar += suben - (int) (cola.reabordados - reabordosAntes);
            return suben;
        }

        /** POI destino al azar a más de 400 m del origen (caminando no necesitaría micro); null sin POIs. */
        private EberthMPoi sortearDestino(PieroGNodo origen, int minutoDelDia) {
            List<EberthMPoi> todos = pois.todos();
            if (todos.isEmpty()) return null;
            int hora = Math.floorMod(minutoDelDia, MINUTOS_POR_DIA) / 60;
            EberthMPoi poi = null;
            for (int intento = 0; intento < 5; intento++) {
                poi = EberthMGeneradorDemanda.sortearDestino(todos, hora, azar);
                if (EberthMIndicePoi.distanciaPuntoSegmento(poi.lon(), poi.lat(), origen.lon, origen.lat,
                        origen.lon, origen.lat) > EberthMIndicePoi.RADIO_CAMINATA_METROS) break;
            }
            return poi;
        }

        /** Ubicaciones físicas (nodos junto a los POIs) donde aparece gente aunque ninguna ruta pase. */
        void setUbicacionesFisicas(List<PieroGNodo> ubicaciones) {
            this.ubicacionesFisicas = List.copyOf(ubicaciones);
            fisicas.clear();
            for (PieroGNodo n : ubicaciones) fisicas.add(n.id);
            registrar(this.ubicacionesFisicas);
        }

        private List<PieroGNodo> ubicacionesFisicas = List.of();
        private final Map<Integer, PieroGNodo> residenciales = new HashMap<>();
        /** Ids de las ubicaciones junto a un POI (no se tratan como origen solo residencial). */
        private final java.util.Set<Integer> fisicas = new java.util.HashSet<>();

        /** Salida al trabajo/estudio (06:00-09:00) y regreso (17:00-20:00) desde calles residenciales. */
        private static boolean horaResidencial(int minutoDelDia) {
            return (minutoDelDia >= 6 * 60 && minutoDelDia < 9 * 60)
                    || (minutoDelDia >= 17 * 60 && minutoDelDia < 20 * 60);
        }

        void setOrigenesResidenciales(List<PieroGNodo> nodosResidenciales) {
            residenciales.clear();
            for (PieroGNodo n : nodosResidenciales) residenciales.put(n.id, n);
            registrar(nodosResidenciales);
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

        double esperaPromedioMinutos() {
            return abordadosConEspera == 0 ? 0 : (double) esperaTotal / abordadosConEspera;
        }

        int abandonos() {
            return abandonos;
        }

        long generados() {
            return generados;
        }

        /** Pasajeros que subieron pero no tienen dónde bajar: deshacen el abordaje y se rinden. */
        void deshacerAbordaje(int cantidad) {
            abordadosSinContar -= cantidad;
            abandonos += cantidad;
        }

        int retirarAbordados() {
            int n = abordadosSinContar;
            abordadosSinContar = 0;
            return n;
        }

        List<EberthMParadaEspera> instantanea() {
            List<EberthMParadaEspera> lista = new ArrayList<>(esperando.size());
            esperando.forEach((id, cola) -> {
                PieroGNodo nodo = nodos.get(id);
                lista.add(new EberthMParadaEspera(nodo.x, nodo.y, cola.total, cola.enTransbordo()));
            });
            return lista;
        }
    }

    private record RutaDesvioKey(String rutaId, int origenId, int destinoId,
                                 int siguienteParada, boolean ida) {}
}
