package trafiqueishon.simulacion;

import trafiqueishon.geo.Edge;
import trafiqueishon.geo.GrafoVial;
import trafiqueishon.geo.Nodo;
import trafiqueishon.geo.Pathfinder;
import trafiqueishon.geo.RutaResult;

import java.util.*;

/**
 * Simulacion de agentes con estado primitivo contiguo para reducir presion GC.
 * El hilo de simulacion es propietario del estado; la UI lee snapshots al final del tick.
 */
public final class SimuladorTrafico {
    public static final int MAX_VEHICULOS = 50_000;
    private static final double PASO_SIMULACION_SEGUNDOS = 0.25;
    private static final TipoVehiculo[] TIPOS_VEHICULO = TipoVehiculo.values();
    private static final double[] VELOCIDAD_TIPO = {1.0, 1.25, 0.85, 0.8, 0.55, 0.75};

    private final List<Destino> destinos;
    private final GrafoVial grafo;
    private final Pathfinder pathfinder;
    private final MatrizDemandaOD matrizOD;
    private final Random random = new Random();
    private final PoolVehiculos poolVehiculos = new PoolVehiculos();
    private final EnumMap<TipoVehiculo, Double> tasas = new EnumMap<>(TipoVehiculo.class);
    private final double[] acumuladorDemanda = new double[TIPOS_VEHICULO.length];
    private final List<RutaDemanda>[] rutasPorTipo;
    private final Map<Long, RutaDemanda>[] rutasPorParOD;
    private final Set<Integer> aristasRutaPreferida = new HashSet<>();
    private List<String> empresasConfiguradas = List.of();
    private final Map<String, Integer> indiceEmpresa = new HashMap<>();
    private final List<AccesoSpawner>[] accesosSpawnerPorTipo;
    private final List<Nodo> nodosAcceso;
    private final Map<String, Integer> indicePunto = new HashMap<>();
    private final List<ServicioMicro> serviciosMicros = new ArrayList<>();
    private final int[] ocupacionAristas;
    private final int[] aristasOcupadas;
    private final int[] ordenVehiculos = new int[MAX_VEHICULOS];
    private final int[] liderVehiculo = new int[MAX_VEHICULOS];
    private final List<SemaforoInteligente> semaforos = new ArrayList<>();
    private final List<Nodo> interseccionesSugeridas = new ArrayList<>();
    private final SemaforoInteligente[] semaforosPorNodo;
    private final double[] prioridadSemaforo;
    private final int[] colasEjeA;
    private final int[] colasEjeB;
    private final SpatialGrid rejillaEspacial = new SpatialGrid(64, 64);
    private int cantidadAristasOcupadas;

    private Edge[] rutaVehiculo = new Edge[8192];
    private int[] indiceArista = new int[8192];
    private double[] progresoArista = new double[8192];
    private double[] velocidadVehiculo = new double[8192];
    private byte[] tipoVehiculo = new byte[8192];
    private byte[] carrilVehiculo = new byte[8192];
    private int[] servicioMicro = new int[8192];
    private double[] tiempoParadaVehiculo = new double[8192];
    private double[] tiempoBloqueadoVehiculo = new double[8192];
    private float[] xsSnapshot = new float[8192];
    private float[] ysSnapshot = new float[8192];
    private byte[] tiposSnapshot = new byte[8192];
    private int[] empresasSnapshot = new int[8192];
    private int cantidadVehiculos;
    private int limiteVehiculos = 10_000;
    private volatile boolean ejecutando;
    private boolean iniciada;
    private double escalaTiempo = 1.0;
    private double tiempoSimulado;
    private long viajesCompletados;

    @SuppressWarnings("unchecked")
    public SimuladorTrafico(GrafoVial grafo, List<PuntoInteres> puntos) {
        this.grafo = Objects.requireNonNull(grafo);
        Objects.requireNonNull(puntos);
        pathfinder = new Pathfinder(grafo);
        destinos = new ArrayList<>();
        rutasPorTipo = (List<RutaDemanda>[]) new List<?>[TIPOS_VEHICULO.length];
        rutasPorParOD = (Map<Long, RutaDemanda>[]) new Map<?, ?>[TIPOS_VEHICULO.length];
        accesosSpawnerPorTipo =
                (List<AccesoSpawner>[]) new List<?>[TIPOS_VEHICULO.length];
        for (TipoVehiculo tipo : TIPOS_VEHICULO) {
            tasas.put(tipo, 0.0);
            rutasPorTipo[tipo.ordinal()] = new ArrayList<>();
            rutasPorParOD[tipo.ordinal()] = new HashMap<>();
            accesosSpawnerPorTipo[tipo.ordinal()] = new ArrayList<>();
        }

        ocupacionAristas = new int[grafo.numEdges()];
        aristasOcupadas = new int[grafo.numEdges()];
        semaforosPorNodo = new SemaforoInteligente[grafo.numNodos()];
        prioridadSemaforo = new double[grafo.numNodos()];
        colasEjeA = new int[grafo.numNodos()];
        colasEjeB = new int[grafo.numNodos()];

        Set<Integer> nodosIncluidos = new HashSet<>();
        for (PuntoInteres punto : puntos) {
            Nodo nodo = grafo.nodoMasCercanoLonLat(punto.lon(), punto.lat());
            if (nodo != null && nodosIncluidos.add(nodo.id)) {
                destinos.add(new Destino(punto, nodo));
            }
        }
        if (destinos.size() < 2) {
            throw new IllegalArgumentException(
                    "Los puntos de interes deben corresponder a dos nodos viales distintos.");
        }
        matrizOD = new MatrizDemandaOD(
                destinos.stream().map(Destino::punto).toList(),
                Math.min(4, destinos.size()));
        for (int i = 0; i < destinos.size(); i++) {
            indicePunto.put(destinos.get(i).punto.id(), i);
        }
        nodosAcceso = detectarNodosAcceso();
        detectarSemaforos();
    }

    private long claveParOD(int origen, int destino) {
        return ((long) origen << 32) | (destino & 0xffffffffL);
    }

    public synchronized void configurarDemanda(Collection<DemandaVehicular> demanda) {
        Objects.requireNonNull(demanda);
        for (TipoVehiculo tipo : TIPOS_VEHICULO) tasas.put(tipo, 0.0);
        for (DemandaVehicular config : demanda) {
            tasas.put(config.tipo(), config.vehiculosPorMinuto());
        }
    }

    public synchronized void configurarMicros(Collection<RutaMicro> rutas) {
        Objects.requireNonNull(rutas);
        List<RutaMicro> configuraciones = List.copyOf(rutas);
        List<ServicioMicro> serviciosNuevos = new ArrayList<>(configuraciones.size());
        for (RutaMicro ruta : configuraciones) serviciosNuevos.add(crearServicio(ruta));
        List<String> empresasNuevas =
                configuraciones.stream().map(RutaMicro::empresa).distinct().toList();
        Map<String, Integer> indicesNuevos = new HashMap<>();
        for (int i = 0; i < empresasNuevas.size(); i++) {
            indicesNuevos.put(empresasNuevas.get(i), i);
        }
        serviciosMicros.clear();
        serviciosMicros.addAll(serviciosNuevos);
        empresasConfiguradas = empresasNuevas;
        indiceEmpresa.clear();
        indiceEmpresa.putAll(indicesNuevos);
    }

    public synchronized void setLimiteVehiculos(int limite) {
        if (limite < 1 || limite > MAX_VEHICULOS) {
            throw new IllegalArgumentException("La flota maxima debe estar entre 1 y "
                    + MAX_VEHICULOS + ".");
        }
        limiteVehiculos = limite;
    }

    /** Arranca con flota inicial para pruebas y escenarios preconfigurados. */
    public synchronized void iniciar(int cantidadInicial) {
        if (cantidadInicial < 0 || cantidadInicial > MAX_VEHICULOS) {
            throw new IllegalArgumentException("La flota inicial debe estar entre 0 y "
                    + MAX_VEHICULOS + ".");
        }
        if (cantidadInicial > limiteVehiculos) {
            throw new IllegalArgumentException("La flota inicial supera el maximo configurado.");
        }
        prepararRutas(cantidadInicial > 0 && !hayDemanda());
        while (cantidadVehiculos < cantidadInicial) {
            TipoVehiculo tipo = tipoAleatorioPorDemanda();
            crearVehiculo(tipo, elegirRutaSpawner(tipo), -1);
        }
        iniciada = true;
        ejecutando = true;
    }

    public synchronized void pausar() { ejecutando = false; }

    public synchronized void reanudar() {
        if (iniciada) ejecutando = true;
    }

    public synchronized void reiniciar() {
        ejecutando = false;
        iniciada = false;
        poolVehiculos.reiniciar();
        cantidadVehiculos = poolVehiculos.activos();
        cantidadAristasOcupadas = 0;
        Arrays.fill(acumuladorDemanda, 0);
        Arrays.fill(ocupacionAristas, 0);
        Arrays.fill(progresoArista, 0, Math.min(8192, progresoArista.length), 0);
        Arrays.fill(velocidadVehiculo, 0);
        serviciosMicros.forEach(servicio -> {
            servicio.unidadesActivas = 0;
            servicio.tiempoHastaSalida = 0;
        });
        semaforos.forEach(SemaforoInteligente::reiniciarCiclo);
        for (Edge edge : grafo.getEdges()) edge.factorTrafico = 1.0;
        tiempoSimulado = 0;
        viajesCompletados = 0;
    }

    public synchronized void setEscalaTiempo(double escala) {
        if (!Double.isFinite(escala) || escala < 1 || escala > 20) {
            throw new IllegalArgumentException("La velocidad debe estar entre 1x y 20x.");
        }
        escalaTiempo = escala;
    }

    public synchronized void actualizar(double segundosReales) {
        if (!ejecutando || !Double.isFinite(segundosReales) || segundosReales <= 0) return;
        double restante = Math.min(segundosReales, 0.25) * escalaTiempo;
        while (restante > 0) {
            double paso = Math.min(restante, PASO_SIMULACION_SEGUNDOS);
            avanzar(paso);
            restante -= paso;
        }
    }

    private void avanzar(double segundos) {
        tiempoSimulado += segundos;
        generarDemanda(segundos);
        despacharMicros(segundos);
        actualizarCongestion();
        actualizarSemaforos(segundos);
        calcularLideres();
        for (int i = 0; i < cantidadVehiculos;) {
            int antes = cantidadVehiculos;
            avanzarVehiculo(i, segundos);
            if (cantidadVehiculos == antes || i >= cantidadVehiculos) i++;
        }
    }

    private void generarDemanda(double segundos) {
        for (TipoVehiculo tipo : TIPOS_VEHICULO) {
            int ordinal = tipo.ordinal();
            acumuladorDemanda[ordinal] += tasas.get(tipo) * segundos / 60.0;
            while (acumuladorDemanda[ordinal] >= 1
                    && cantidadVehiculos < limiteVehiculos) {
                crearVehiculo(tipo, elegirRutaSpawner(tipo), -1);
                acumuladorDemanda[ordinal] -= 1;
            }
            if (cantidadVehiculos >= limiteVehiculos) {
                acumuladorDemanda[ordinal] %= 1.0;
            }
        }
    }

    private void despacharMicros(double segundos) {
        for (int i = 0; i < serviciosMicros.size(); i++) {
            ServicioMicro servicio = serviciosMicros.get(i);
            servicio.tiempoHastaSalida -= segundos;
            if (servicio.tiempoHastaSalida > 0
                    || servicio.unidadesActivas >= servicio.configuracion.unidades()
                    || cantidadVehiculos >= limiteVehiculos) continue;
            crearVehiculo(TipoVehiculo.MICRO, servicio.ruta, i);
            servicio.unidadesActivas++;
            servicio.tiempoHastaSalida += servicio.configuracion.frecuenciaMinutos() * 60.0;
        }
    }

    private void actualizarCongestion() {
        for (int i = 0; i < cantidadAristasOcupadas; i++) {
            int id = aristasOcupadas[i];
            ocupacionAristas[id] = 0;
            grafo.getEdges().get(id).factorTrafico = 1.0;
        }
        cantidadAristasOcupadas = 0;
        for (int i = 0; i < cantidadVehiculos; i++) {
            Edge edge = rutaVehiculo[i];
            int id = edge.id;
            if (ocupacionAristas[id]++ == 0) aristasOcupadas[cantidadAristasOcupadas++] = id;
        }
        for (int i = 0; i < cantidadAristasOcupadas; i++) {
            Edge edge = grafo.getEdges().get(aristasOcupadas[i]);
            double capacidad = Math.max(1, edge.longitudMetros() * edge.lanes / 7.0);
            edge.factorTrafico = 1.0 + Math.min(3.0, ocupacionAristas[edge.id] / capacidad) * 0.8;
        }
    }

    private void avanzarVehiculo(int vehiculo, double segundos) {
        Edge edge = rutaVehiculo[vehiculo];
        TipoVehiculo tipo = TIPOS_VEHICULO[tipoVehiculo[vehiculo]];
        if (tiempoParadaVehiculo[vehiculo] > 0) {
            tiempoParadaVehiculo[vehiculo] = Math.max(0,
                    tiempoParadaVehiculo[vehiculo] - segundos);
            velocidadVehiculo[vehiculo] = 0;
            return;
        }
        double velocidadLibre = edge.velocidadLibreMps()
                * VELOCIDAD_TIPO[tipo.ordinal()] / edge.factorTrafico;
        double velocidad = velocidadVehiculo[vehiculo];
        double distanciaSeguridad = tipo == TipoVehiculo.MOTOTAXI ? 1.5 : 2.5;
        double tiempoReaccion = tipo == TipoVehiculo.MOTOTAXI ? 0.6 : 1.2;
        double aceleracionMaxima = tipo == TipoVehiculo.MOTOTAXI ? 2.2 : 1.1;
        double frenadoComodo = tipo == TipoVehiculo.MOTOTAXI ? 2.5 : 2.0;

        int lider = liderVehiculo[vehiculo];
        if (tipo == TipoVehiculo.MOTOTAXI && lider >= 0 && edge.lanes > 1
                && progresoArista[lider] - progresoArista[vehiculo] < 8
                && velocidad < velocidadLibre * 0.7) {
            tiempoBloqueadoVehiculo[vehiculo] += segundos;
            if (tiempoBloqueadoVehiculo[vehiculo] >= 1.0) {
                int carrilNuevo = (Byte.toUnsignedInt(carrilVehiculo[vehiculo]) + 1)
                        % Math.max(1, edge.lanes);
                if (carrilDisponible(edge, carrilNuevo, progresoArista[vehiculo], 12)) {
                    carrilVehiculo[vehiculo] = (byte) carrilNuevo;
                    tiempoBloqueadoVehiculo[vehiculo] = 0;
                    lider = -1;
                }
            }
        } else {
            tiempoBloqueadoVehiculo[vehiculo] = 0;
        }
        double velocidadLider = lider >= 0 ? velocidadVehiculo[lider] : 0;
        double espacio = lider >= 0
                ? progresoArista[lider] - progresoArista[vehiculo] - distanciaSeguridad
                : Double.POSITIVE_INFINITY;

        SemaforoInteligente semaforo = semaforosPorNodo[edge.destino.id];
        boolean luzRoja = semaforo != null && !semaforo.permite(edge)
                && progresoArista[vehiculo] < edge.longitudMetros() - 3.0;
        if (luzRoja) {
            double distanciaLinea = edge.longitudMetros() - progresoArista[vehiculo] - 3.0;
            if (distanciaLinea < espacio) {
                espacio = distanciaLinea;
                velocidadLider = 0;
            }
        }
        double aceleracion = ModeloIDM.aceleracion(velocidad, velocidadLider,
                Math.max(0, espacio), Math.max(1, velocidadLibre), tiempoReaccion,
                distanciaSeguridad, aceleracionMaxima, frenadoComodo);

        double nuevaVelocidad = Math.clamp(velocidad + aceleracion * segundos,
                0, velocidadLibre);
        double avance = (velocidad + nuevaVelocidad) * 0.5 * segundos;
        if (lider >= 0 && espacio != Double.POSITIVE_INFINITY) {
            avance = Math.min(avance, Math.max(0, espacio));
        }
        double limiteArista = luzRoja
                ? Math.max(0, edge.longitudMetros() - 3.0) : edge.longitudMetros();
        double progresoNuevo = Math.min(limiteArista, progresoArista[vehiculo] + avance);
        velocidadVehiculo[vehiculo] = progresoNuevo >= limiteArista
                ? (luzRoja ? 0 : nuevaVelocidad) : nuevaVelocidad;
        progresoArista[vehiculo] = progresoNuevo;

        if (!luzRoja && progresoArista[vehiculo] >= edge.longitudMetros()) {
            int servicioActivo = servicioMicro[vehiculo];
            if (servicioActivo >= 0
                    && serviciosMicros.get(servicioActivo).nodosParada.contains(edge.destino.id)) {
                indiceArista[vehiculo]++;
                if (indiceArista[vehiculo] < rutaVehiculoFinal(vehiculo).size()) {
                    rutaVehiculo[vehiculo] = rutaVehiculoFinal(vehiculo)
                            .get(indiceArista[vehiculo]);
                }
                progresoArista[vehiculo] = 0;
                tiempoParadaVehiculo[vehiculo] = 10;
                velocidadVehiculo[vehiculo] = 0;
                return;
            }
            indiceArista[vehiculo]++;
            if (indiceArista[vehiculo] >= rutaVehiculoFinal(vehiculo).size()) {
                viajesCompletados++;
                int servicio = servicioMicro[vehiculo];
                if (servicio >= 0) {
                    serviciosMicros.get(servicio).unidadesActivas--;
                    removerVehiculo(vehiculo);
                    return;
                }
                rutaPorVehiculo[vehiculo] = elegirRuta(tipo);
                indiceArista[vehiculo] = 0;
            }
            progresoArista[vehiculo] = 0;
            velocidadVehiculo[vehiculo] = nuevaVelocidad;
            rutaVehiculo[vehiculo] = rutaVehiculoFinal(vehiculo)
                    .get(indiceArista[vehiculo]);
            carrilVehiculo[vehiculo] = (byte) (Byte.toUnsignedInt(carrilVehiculo[vehiculo])
                    % Math.max(1, rutaVehiculo[vehiculo].lanes));
        }
    }

    private boolean carrilDisponible(Edge arista, int carril, double progreso, double margen) {
        for (int i = 0; i < cantidadVehiculos; i++) {
            if (rutaVehiculo[i].id == arista.id
                    && Byte.toUnsignedInt(carrilVehiculo[i]) == carril
                    && Math.abs(progresoArista[i] - progreso) < margen) return false;
        }
        return true;
    }

    private void calcularLideres() {
        for (int i = 0; i < cantidadVehiculos; i++) ordenVehiculos[i] = i;
        ordenarVehiculos(0, cantidadVehiculos - 1);
        Arrays.fill(liderVehiculo, 0, cantidadVehiculos, -1);
        for (int i = 0; i + 1 < cantidadVehiculos; i++) {
            int actual = ordenVehiculos[i];
            int siguiente = ordenVehiculos[i + 1];
            if (rutaVehiculo[actual].id == rutaVehiculo[siguiente].id
                    && carrilVehiculo[actual] == carrilVehiculo[siguiente]) {
                liderVehiculo[actual] = siguiente;
            }
        }
    }

    private void ordenarVehiculos(int bajo, int alto) {
        int i = bajo;
        int j = alto;
        if (bajo >= alto) return;
        int pivote = ordenVehiculos[(bajo + alto) >>> 1];
        while (i <= j) {
            while (compararVehiculos(ordenVehiculos[i], pivote) < 0) i++;
            while (compararVehiculos(ordenVehiculos[j], pivote) > 0) j--;
            if (i <= j) {
                int temporal = ordenVehiculos[i];
                ordenVehiculos[i++] = ordenVehiculos[j];
                ordenVehiculos[j--] = temporal;
            }
        }
        if (bajo < j) ordenarVehiculos(bajo, j);
        if (i < alto) ordenarVehiculos(i, alto);
    }

    private int compararVehiculos(int a, int b) {
        int porArista = Integer.compare(rutaVehiculo[a].id, rutaVehiculo[b].id);
        if (porArista != 0) return porArista;
        int porCarril = Byte.compare(carrilVehiculo[a], carrilVehiculo[b]);
        return porCarril != 0 ? porCarril
                : Double.compare(progresoArista[a], progresoArista[b]);
    }

    private void detectarSemaforos() {
        List<CandidatoSemaforo> candidatos = new ArrayList<>();
        for (Nodo nodo : grafo.getNodos()) {
            Set<Integer> vecinos = new HashSet<>();
            Set<String> jerarquias = new HashSet<>();
            Set<Integer> aproximacionesPrincipales = new HashSet<>();
            for (Edge edge : nodo.salientes) {
                agregarAproximacion(edge.destino.id, edge.highway,
                        vecinos, jerarquias, aproximacionesPrincipales);
            }
            for (Edge edge : nodo.entrantes) {
                agregarAproximacion(edge.origen.id, edge.highway,
                        vecinos, jerarquias, aproximacionesPrincipales);
            }
            int grado = vecinos.size();
            if (grado < 3 || aproximacionesPrincipales.size() < 2) continue;

            double jerarquia = jerarquias.stream().mapToDouble(this::pesoJerarquia)
                    .max().orElse(0);
            double densidadVialPrincipal = Math.min(3.0,
                    aproximacionesPrincipales.size() / 2.0);
            double volumen = Math.min(3.0,
                    estimarVolumenLocal(nodo) + densidadVialPrincipal);
            double puntaje = PrioridadSemaforo.puntaje(grado, jerarquia, volumen);
            prioridadSemaforo[nodo.id] = puntaje;
            if (PrioridadSemaforo.superaUmbral(puntaje)) {
                candidatos.add(new CandidatoSemaforo(nodo, puntaje));
            }
        }

        candidatos.sort((a, b) -> Double.compare(b.puntaje, a.puntaje));
        List<Nodo> elegidos = new ArrayList<>();
        for (CandidatoSemaforo candidato : candidatos) {
            if (elegidos.size() >= 60) break;
            boolean muyCercano = elegidos.stream().anyMatch(nodo ->
                    distanciaMetros(nodo, candidato.nodo) < 500);
            if (muyCercano) continue;
            elegidos.add(candidato.nodo);
            interseccionesSugeridas.add(candidato.nodo);
            SemaforoInteligente semaforo = new SemaforoInteligente(
                    candidato.nodo, 0x4A554C49414341L + candidato.nodo.id);
            semaforos.add(semaforo);
            semaforosPorNodo[candidato.nodo.id] = semaforo;
        }
    }

    public synchronized int colocarSemaforosSugeridos() {
        int agregados = 0;
        for (Nodo nodo : interseccionesSugeridas) {
            if (semaforosPorNodo[nodo.id] != null) continue;
            SemaforoInteligente semaforo =
                    new SemaforoInteligente(nodo, 0x4A554C49414341L + nodo.id);
            semaforos.add(semaforo);
            semaforosPorNodo[nodo.id] = semaforo;
            agregados++;
        }
        return agregados;
    }

    public synchronized boolean quitarSemaforo(int nodoId) {
        if (nodoId < 0 || nodoId >= semaforosPorNodo.length) {
            throw new IndexOutOfBoundsException("Nodo fuera del grafo: " + nodoId);
        }
        SemaforoInteligente semaforo = semaforosPorNodo[nodoId];
        if (semaforo == null) return false;
        semaforosPorNodo[nodoId] = null;
        semaforos.remove(semaforo);
        return true;
    }

    public synchronized boolean tieneSemaforo(int nodoId) {
        if (nodoId < 0 || nodoId >= semaforosPorNodo.length) {
            throw new IndexOutOfBoundsException("Nodo fuera del grafo: " + nodoId);
        }
        return semaforosPorNodo[nodoId] != null;
    }

    private List<Nodo> detectarNodosAcceso() {
        double minX = grafo.getNodos().stream().mapToDouble(nodo -> nodo.x).min().orElse(0);
        double maxX = grafo.getNodos().stream().mapToDouble(nodo -> nodo.x).max().orElse(1);
        double minY = grafo.getNodos().stream().mapToDouble(nodo -> nodo.y).min().orElse(0);
        double maxY = grafo.getNodos().stream().mapToDouble(nodo -> nodo.y).max().orElse(1);
        List<Nodo> candidatos = new ArrayList<>();
        for (Nodo nodo : grafo.getNodos()) {
            long salidas = nodo.salientes.stream()
                    .filter(edge -> edge.destino != nodo).count();
            double cercaniaBorde = Math.min(
                    Math.min(nodo.x - minX, maxX - nodo.x),
                    Math.min(nodo.y - minY, maxY - nodo.y));
            double distanciaCentro = Math.hypot(
                    (nodo.x - (minX + maxX) / 2) / Math.max(1e-9, maxX - minX),
                    (nodo.y - (minY + maxY) / 2) / Math.max(1e-9, maxY - minY));
            if (salidas > 0 && (cercaniaBorde <= 0.035
                    || nodo.salientes.size() + nodo.entrantes.size() <= 2
                    && distanciaCentro >= 0.30)) {
                candidatos.add(nodo);
            }
        }
        candidatos.sort(Comparator.comparingDouble(nodo -> Math.atan2(
                nodo.y - (minY + maxY) / 2, nodo.x - (minX + maxX) / 2)));
        if (candidatos.size() <= 24) return List.copyOf(candidatos);

        List<Nodo> accesos = new ArrayList<>(24);
        for (int i = 0; i < 24; i++) {
            int indice = (int) Math.round((double) i * (candidatos.size() - 1) / 23);
            Nodo candidato = candidatos.get(indice);
            if (accesos.isEmpty() || accesos.getLast() != candidato) accesos.add(candidato);
        }
        return List.copyOf(accesos);
    }

    private void agregarAproximacion(int hasta, String highway,
                                     Set<Integer> vecinos, Set<String> jerarquias,
                                     Set<Integer> aproximacionesPrincipales) {
        vecinos.add(hasta);
        if (pesoJerarquia(highway) > 0) {
            jerarquias.add(normalizarJerarquia(highway));
            aproximacionesPrincipales.add(hasta);
        }
    }

    private String normalizarJerarquia(String highway) {
        if (highway.endsWith("_link")) return highway.substring(0, highway.length() - 5);
        return highway;
    }

    private double pesoJerarquia(String highway) {
        return switch (normalizarJerarquia(highway == null ? "" : highway)) {
            case "trunk" -> 3.0;
            case "primary" -> 2.5;
            case "secondary" -> 1.5;
            default -> 0;
        };
    }

    private double estimarVolumenLocal(Nodo nodo) {
        double volumen = 0;
        for (Destino destino : destinos) {
            if (distanciaMetros(nodo, destino.nodo) <= 300) {
                volumen += matrizOD.probabilidadOrigen(destino.punto.id())
                        * (0.5 + Math.log1p(destino.punto.peso()));
            }
        }
        return Math.min(3.0, volumen * 10);
    }

    private double distanciaMetros(Nodo a, Nodo b) {
        double lat1 = Math.toRadians(a.lat);
        double lat2 = Math.toRadians(b.lat);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.lon - a.lon);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6_371_000 * Math.asin(Math.sqrt(h));
    }

    public synchronized void paraCadaArista(
            java.util.function.Consumer<Edge> consumidor) {
        Objects.requireNonNull(consumidor);
        grafo.getEdges().forEach(consumidor);
    }

    private void actualizarSemaforos(double segundos) {
        Arrays.fill(colasEjeA, 0);
        Arrays.fill(colasEjeB, 0);
        for (int i = 0; i < cantidadVehiculos; i++) {
            Edge edge = rutaVehiculo[i];
            SemaforoInteligente semaforo = semaforosPorNodo[edge.destino.id];
            if (semaforo == null || edge.longitudMetros() - progresoArista[i] > 35
                    || velocidadVehiculo[i] > 1.0) continue;
            if (Math.abs(edge.origen.x - edge.destino.x)
                    >= Math.abs(edge.origen.y - edge.destino.y)) {
                colasEjeA[edge.destino.id]++;
            } else {
                colasEjeB[edge.destino.id]++;
            }
        }
        for (SemaforoInteligente semaforo : semaforos) {
            int nodo = semaforo.nodo().id;
            semaforo.actualizar(segundos, colasEjeA[nodo], colasEjeB[nodo]);
        }
    }

    private List<Edge> rutaVehiculoFinal(int vehiculo) {
        return rutaPorVehiculo[vehiculo];
    }

    @SuppressWarnings("unchecked")
    private List<Edge>[] rutaPorVehiculo = (List<Edge>[]) new List<?>[8192];

    private void removerVehiculo(int indice) {
        int ultimo = poolVehiculos.liberar(indice);
        cantidadVehiculos = poolVehiculos.activos();
        if (indice == ultimo) return;
        rutaVehiculo[indice] = rutaVehiculo[ultimo];
        rutaPorVehiculo[indice] = rutaPorVehiculo[ultimo];
        indiceArista[indice] = indiceArista[ultimo];
        progresoArista[indice] = progresoArista[ultimo];
        velocidadVehiculo[indice] = velocidadVehiculo[ultimo];
        tiempoParadaVehiculo[indice] = tiempoParadaVehiculo[ultimo];
        tiempoBloqueadoVehiculo[indice] = tiempoBloqueadoVehiculo[ultimo];
        tipoVehiculo[indice] = tipoVehiculo[ultimo];
        carrilVehiculo[indice] = carrilVehiculo[ultimo];
        servicioMicro[indice] = servicioMicro[ultimo];
    }

    private void crearVehiculo(TipoVehiculo tipo, List<Edge> ruta, int servicio) {
        if (tipo == null || ruta == null || ruta.isEmpty()) {
            throw new IllegalStateException(
                    "No se puede crear un vehiculo sin tipo y una ruta vial valida.");
        }
        asegurarCapacidad(cantidadVehiculos + 1);
        int indice = poolVehiculos.adquirir(limiteVehiculos);
        cantidadVehiculos = poolVehiculos.activos();
        rutaPorVehiculo[indice] = ruta;
        rutaVehiculo[indice] = ruta.get(0);
        indiceArista[indice] = 0;
        progresoArista[indice] = random.nextDouble()
                * Math.min(15, ruta.get(0).longitudMetros() * 0.8);
        velocidadVehiculo[indice] = 0;
        tiempoParadaVehiculo[indice] = 0;
        tiempoBloqueadoVehiculo[indice] = 0;
        tipoVehiculo[indice] = (byte) tipo.ordinal();
        carrilVehiculo[indice] = (byte) random.nextInt(Math.max(1, ruta.get(0).lanes));
        servicioMicro[indice] = servicio;
        empresasSnapshot[indice] = servicio >= 0
                ? indiceEmpresa.getOrDefault(
                        serviciosMicros.get(servicio).configuracion.empresa(), -1)
                : -1;
    }

    private void asegurarCapacidad(int requerida) {
        if (requerida <= rutaVehiculo.length) return;
        int nueva = Math.min(MAX_VEHICULOS,
                Math.max(requerida, rutaVehiculo.length * 2));
        rutaVehiculo = Arrays.copyOf(rutaVehiculo, nueva);
        rutaPorVehiculo = Arrays.copyOf(rutaPorVehiculo, nueva);
        indiceArista = Arrays.copyOf(indiceArista, nueva);
        progresoArista = Arrays.copyOf(progresoArista, nueva);
        velocidadVehiculo = Arrays.copyOf(velocidadVehiculo, nueva);
        tipoVehiculo = Arrays.copyOf(tipoVehiculo, nueva);
        carrilVehiculo = Arrays.copyOf(carrilVehiculo, nueva);
        servicioMicro = Arrays.copyOf(servicioMicro, nueva);
        tiempoParadaVehiculo = Arrays.copyOf(tiempoParadaVehiculo, nueva);
        tiempoBloqueadoVehiculo = Arrays.copyOf(tiempoBloqueadoVehiculo, nueva);
        xsSnapshot = Arrays.copyOf(xsSnapshot, nueva);
        ysSnapshot = Arrays.copyOf(ysSnapshot, nueva);
        tiposSnapshot = Arrays.copyOf(tiposSnapshot, nueva);
        empresasSnapshot = Arrays.copyOf(empresasSnapshot, nueva);
    }

    private ServicioMicro crearServicio(RutaMicro ruta) {
        prepararRutas(false);
        List<Edge> recorrido = new ArrayList<>();
        Set<Integer> nodosParada = new HashSet<>();
        for (int i = 1; i < ruta.puntosIntermedios().size(); i++) {
            Destino origen = destinoPorId(ruta.puntosIntermedios().get(i - 1), TipoVehiculo.MICRO);
            Destino destino = destinoPorId(ruta.puntosIntermedios().get(i), TipoVehiculo.MICRO);
            RutaResult tramo = pathfinder.ruta(origen.nodo, destino.nodo,
                    edge -> TipoVehiculo.MICRO.puedeCircular(edge.highway));
            if (tramo == null || tramo.esVacia()) {
                throw new IllegalArgumentException("Sin ruta permitida para micro entre "
                        + origen.punto.id() + " y " + destino.punto.id() + ".");
            }
            recorrido.addAll(tramo.getEdges());
            if (i < ruta.puntosIntermedios().size() - 1) {
                nodosParada.add(tramo.getEdges()
                        .get(tramo.getEdges().size() - 1).destino.id);
            }
        }
        if (recorrido.isEmpty()) throw new IllegalArgumentException("La ruta del micro esta vacia.");
        return new ServicioMicro(ruta, List.copyOf(recorrido), Set.copyOf(nodosParada));
    }

    private Destino destinoPorId(String id, TipoVehiculo tipo) {
        PuntoInteres punto = destinos.stream().map(Destino::punto)
                .filter(destino -> destino.id().equals(id))
                .findFirst().orElseThrow(() ->
                        new IllegalArgumentException("No existe el punto de ruta: " + id));
        Nodo nodo = grafo.nodoMasCercanoLonLat(punto.lon(), punto.lat(),
                edge -> tipo.puedeCircular(edge.highway));
        if (nodo == null) {
            throw new IllegalArgumentException("No hay una via permitida cerca de " + punto.nombre());
        }
        return new Destino(punto, nodo);
    }

    private List<Edge> elegirRuta(TipoVehiculo tipo) {
        List<RutaDemanda> opciones = rutasPorTipo[tipo.ordinal()];
        if (opciones.isEmpty()) throw new IllegalStateException(
                "No hay rutas disponibles para " + tipo.etiqueta() + ".");
        if (!aristasRutaPreferida.isEmpty()) {
            return elegirRutaConPreferencia(opciones);
        }
        PuntoInteres origen = matrizOD.elegirOrigen(random);
        String destinoId = matrizOD.elegirDestino(origen.id(), random).id();
        RutaDemanda rutaOD = rutasPorParOD[tipo.ordinal()].get(
                claveParOD(indicePunto.get(origen.id()), indicePunto.get(destinoId)));
        if (rutaOD != null) return rutaOD.edges;

        long pesoTotal = opciones.stream().mapToLong(RutaDemanda::peso).sum();
        long elegido = random.nextLong(pesoTotal);
        for (RutaDemanda opcion : opciones) {
            elegido -= opcion.peso;
            if (elegido < 0) return opcion.edges;
        }
        return opciones.get(opciones.size() - 1).edges;
    }

    private List<Edge> elegirRutaConPreferencia(List<RutaDemanda> opciones) {
        return elegirRutaPonderada(opciones);
    }

    private List<Edge> elegirRutaPonderada(List<RutaDemanda> opciones) {
        double pesoTotal = 0;
        for (RutaDemanda opcion : opciones) pesoTotal += pesoRuta(opcion);
        double elegido = random.nextDouble() * pesoTotal;
        for (RutaDemanda opcion : opciones) {
            elegido -= pesoRuta(opcion);
            if (elegido < 0) return opcion.edges;
        }
        return opciones.getLast().edges;
    }

    private double pesoRuta(RutaDemanda opcion) {
        if (aristasRutaPreferida.isEmpty()) return opcion.peso;
        int coincidencias = 0;
        for (Edge edge : opcion.edges) {
            if (aristasRutaPreferida.contains(edge.id)) coincidencias++;
        }
        double proporcionCoincidente = opcion.edges.isEmpty()
                ? 0 : (double) coincidencias / opcion.edges.size();
        return opcion.peso * (1 + 4 * proporcionCoincidente);
    }

    public synchronized void configurarRutaPreferida(RutaResult ruta) {
        aristasRutaPreferida.clear();
        if (ruta != null) {
            ruta.getEdges().forEach(edge -> aristasRutaPreferida.add(edge.id));
        }
    }

    private List<Edge> elegirRutaSpawner(TipoVehiculo tipo) {
        List<AccesoSpawner> accesos = accesosSpawnerPorTipo[tipo.ordinal()];
        if (accesos.isEmpty()) return elegirRuta(tipo);
        AccesoSpawner acceso = accesos.get(random.nextInt(accesos.size()));
        return elegirRutaPonderada(acceso.rutas);
    }

    private void prepararRutas(boolean prepararVehiculoSemilla) {
        for (TipoVehiculo tipo : TIPOS_VEHICULO) {
            List<RutaDemanda> rutas = rutasPorTipo[tipo.ordinal()];
            boolean necesitaRutas = tasas.get(tipo) > 0
                    || tipo == TipoVehiculo.MICRO && !serviciosMicros.isEmpty()
                    || tipo == TipoVehiculo.PARTICULAR && prepararVehiculoSemilla;
            if (!necesitaRutas && rutas.isEmpty()) continue;
            List<Destino> destinosTipo = destinos.stream()
                    .map(destino -> {
                        Nodo nodo = grafo.nodoMasCercanoLonLat(
                                destino.punto.lon(), destino.punto.lat(),
                                edge -> tipo.puedeCircular(edge.highway));
                        return nodo == null ? null : new Destino(destino.punto, nodo);
                    })
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (rutas.isEmpty()) {
                for (Destino origen : destinosTipo) {
                    for (Destino destino : destinosTipo) {
                        if (origen.nodo == destino.nodo) continue;
                        RutaResult resultado = pathfinder.ruta(origen.nodo, destino.nodo,
                                edge -> tipo.puedeCircular(edge.highway));
                        if (resultado != null && !resultado.esVacia()) {
                            long peso = (long) origen.punto.peso() * destino.punto.peso();
                            RutaDemanda ruta = new RutaDemanda(
                                    List.copyOf(resultado.getEdges()), peso,
                                    origen.punto.id(), destino.punto.id());
                            rutas.add(ruta);
                            rutasPorParOD[tipo.ordinal()].put(
                                    claveParOD(indicePunto.get(origen.punto.id()),
                                            indicePunto.get(destino.punto.id())), ruta);
                        }
                    }
                }
            }
            if (tasas.get(tipo) > 0
                    || tipo == TipoVehiculo.PARTICULAR && prepararVehiculoSemilla) {
                prepararRutasEntrada(tipo, destinosTipo);
            }
        }
        TipoVehiculo[] tiposConDemanda = Arrays.stream(TIPOS_VEHICULO)
                .filter(tipo -> tasas.get(tipo) > 0).toArray(TipoVehiculo[]::new);
        if (tiposConDemanda.length > 0
                && Arrays.stream(tiposConDemanda)
                .anyMatch(tipo -> rutasPorTipo[tipo.ordinal()].isEmpty())) {
            throw new IllegalStateException("Algun tipo configurado no tiene rutas permitidas.");
        }
    }

    private void prepararRutasEntrada(TipoVehiculo tipo, List<Destino> destinosTipo) {
        List<AccesoSpawner> accesos = accesosSpawnerPorTipo[tipo.ordinal()];
        if (!accesos.isEmpty()) return;
        for (Nodo acceso : nodosAcceso) {
            if (acceso.salientes.stream().noneMatch(edge -> tipo.puedeCircular(edge.highway))) {
                continue;
            }
            List<RutaDemanda> rutas = new ArrayList<>();
            for (Destino destino : destinosTipo) {
                if (destino.nodo == acceso) continue;
                RutaResult resultado = pathfinder.ruta(acceso, destino.nodo,
                        edge -> tipo.puedeCircular(edge.highway));
                if (resultado == null || resultado.esVacia()) continue;
                rutas.add(new RutaDemanda(List.copyOf(resultado.getEdges()),
                        destino.punto.peso(), "acceso_" + acceso.id, destino.punto.id()));
            }
            if (!rutas.isEmpty()) accesos.add(new AccesoSpawner(acceso, List.copyOf(rutas)));
        }
    }

    public synchronized int getCantidadAccesosSpawner(TipoVehiculo tipo) {
        Objects.requireNonNull(tipo);
        return accesosSpawnerPorTipo[tipo.ordinal()].size();
    }

    private TipoVehiculo tipoAleatorioPorDemanda() {
        double total = tasas.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) return TipoVehiculo.PARTICULAR;
        double seleccionado = random.nextDouble() * total;
        for (TipoVehiculo tipo : TIPOS_VEHICULO) {
            seleccionado -= tasas.get(tipo);
            if (seleccionado < 0) return tipo;
        }
        return TipoVehiculo.PARTICULAR;
    }

    private boolean hayDemanda() {
        return tasas.values().stream().anyMatch(tasa -> tasa > 0);
    }

    public synchronized FrameVehiculos getFrame() {
        for (int i = 0; i < cantidadVehiculos; i++) {
            Edge edge = rutaVehiculo[i];
            double fraccion = edge.longitudMetros() <= 0 ? 0
                    : Math.min(1, progresoArista[i] / edge.longitudMetros());
            double longitudX = grafo.getMaxLon() - grafo.getMinLon();
            double longitudY = grafo.getMaxLat() - grafo.getMinLat();
            double lon = edge.origen.lon + (edge.destino.lon - edge.origen.lon) * fraccion;
            double lat = edge.origen.lat + (edge.destino.lat - edge.origen.lat) * fraccion;
            xsSnapshot[i] = (float) (longitudX > 0
                    ? (lon - grafo.getMinLon()) / longitudX : 0);
            ysSnapshot[i] = (float) (longitudY > 0
                    ? (grafo.getMaxLat() - lat) / longitudY : 0);
            tiposSnapshot[i] = tipoVehiculo[i];
            int servicio = servicioMicro[i];
            empresasSnapshot[i] = servicio < 0 ? -1
                    : indiceEmpresa.getOrDefault(
                            serviciosMicros.get(servicio).configuracion.empresa(), -1);
        }
        rejillaEspacial.reconstruir(xsSnapshot, ysSnapshot, cantidadVehiculos);
        return new FrameVehiculos(Arrays.copyOf(xsSnapshot, cantidadVehiculos),
                Arrays.copyOf(ysSnapshot, cantidadVehiculos),
                Arrays.copyOf(tiposSnapshot, cantidadVehiculos),
                Arrays.copyOf(empresasSnapshot, cantidadVehiculos), empresasConfiguradas,
                cantidadVehiculos);
    }

    public synchronized EstadisticasSimulacion getEstadisticas() {
        double velocidadTotal = 0;
        for (int i = 0; i < cantidadVehiculos; i++) {
            Edge edge = rutaVehiculo[i];
            velocidadTotal += edge.velocidadLibreMps()
                    * VELOCIDAD_TIPO[tipoVehiculo[i]] / edge.factorTrafico * 3.6;
        }
        double media = cantidadVehiculos == 0 ? 0 : velocidadTotal / cantidadVehiculos;
        return new EstadisticasSimulacion(cantidadVehiculos, viajesCompletados,
                tiempoSimulado, media, cantidadAristasOcupadas);
    }

    public int getLimiteVehiculos() { return limiteVehiculos; }
    public boolean isEjecutando() { return ejecutando; }
    public synchronized List<SemaforoInteligente> getSemaforos() {
        return List.copyOf(semaforos);
    }
    public synchronized double getPrioridadSemaforo(int nodoId) {
        if (nodoId < 0 || nodoId >= prioridadSemaforo.length) {
            throw new IndexOutOfBoundsException("Nodo fuera del grafo: " + nodoId);
        }
        return prioridadSemaforo[nodoId];
    }

    public synchronized void setAprendizajeSemaforos(boolean activo) {
        semaforos.forEach(semaforo -> semaforo.setAprendizajeActivo(activo));
    }

    public synchronized void paraCadaVehiculoCercano(float x, float y, float radio,
                                                       java.util.function.IntConsumer consumidor) {
        FrameVehiculos frame = getFrame();
        rejillaEspacial.paraCadaCercano(x, y, radio, consumidor);
    }

    public synchronized void paraCadaParVehicularCercano(
            float radioNormalizado,
            SpatialGrid.PairConsumer consumidor) {
        getFrame();
        rejillaEspacial.paraCadaParCercano(radioNormalizado, consumidor);
    }

    public record FrameVehiculos(float[] x, float[] y, byte[] tipos, int[] empresas,
                                 List<String> nombresEmpresas, int cantidad) {
        public FrameVehiculos {
            nombresEmpresas = List.copyOf(nombresEmpresas);
        }
    }
    private record Destino(PuntoInteres punto, Nodo nodo) {}
    private record RutaDemanda(List<Edge> edges, long peso, String origenId, String destinoId) {}
    private record CandidatoSemaforo(Nodo nodo, double puntaje) {}
    private record AccesoSpawner(Nodo nodo, List<RutaDemanda> rutas) {}

    private static final class ServicioMicro {
        private final RutaMicro configuracion;
        private final List<Edge> ruta;
        private final Set<Integer> nodosParada;
        private int unidadesActivas;
        private double tiempoHastaSalida;

        private ServicioMicro(RutaMicro configuracion, List<Edge> ruta,
                              Set<Integer> nodosParada) {
            this.configuracion = configuracion;
            this.ruta = ruta;
            this.nodosParada = nodosParada;
        }
    }
}
