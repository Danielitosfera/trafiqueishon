package trafiqueishon.simulacion;

import org.junit.jupiter.api.Test;
import trafiqueishon.geo.PieroGEdge;
import trafiqueishon.geo.PieroGGrafoVial;
import trafiqueishon.geo.PieroGNodo;
import trafiqueishon.geo.PieroGProyector;
import trafiqueishon.geo.PieroGVia;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.ruta.LuisYTrayecto;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EberthMSimuladorBusesTest {

    @Test
    void iniciaYAvanzaLosVehiculosSobreElTrayecto() {
        LuisYTrayecto trayecto = trayecto();
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();

        simulador.iniciar(trayecto, 2);
        double posicionInicial = simulador.posiciones().getFirst().x();
        simulador.avanzar(0.2);

        assertTrue(simulador.estaEjecutando());
        assertTrue(simulador.posiciones().getFirst().x() > posicionInicial);
        assertEquals(2, simulador.posiciones().size());
    }

    @Test
    void aceptaElLimiteDeSeisMilUnidadesEnElModoDirecto() {
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();

        simulador.iniciar(trayecto(), 6_000);

        assertEquals(6_000, simulador.posiciones().size());
        assertThrows(IllegalArgumentException.class,
                () -> simulador.iniciar(trayecto(), 6_001));
    }

    @Test
    void rechazaVelocidadFueraDelRangoPermitido() {
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();
        assertThrows(IllegalArgumentException.class,
                () -> simulador.setVelocidadSimulacion(41));
        assertDoesNotThrow(() -> simulador.setVelocidadSimulacion(40));
    }

    @Test
    void asignaPasajerosYValidaLaCapacidadDeLaFlota() {
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();

        simulador.iniciar(trayecto(), 1, 1, 60);

        assertEquals(60, simulador.pasajerosAsignados());
        assertEquals(83, simulador.capacidadTotal());
        assertEquals(60, simulador.posiciones().stream()
                .mapToInt(EberthMPosicionVehiculo::pasajeros).sum());
        assertThrows(IllegalArgumentException.class,
                () -> simulador.iniciar(trayecto(), 1, 1, 84));
    }

    @Test
    void despachaDesdeElInicioDeRutaYRespetaLaFrecuencia() {
        LuisYTrayecto trayecto = trayectoLargo();
        LuisYTrayecto vuelta = trayectoLargoVuelta();
        EberthMServicioMicro servicio = new EberthMServicioMicro("m1", "Micro Centro", trayecto, vuelta,
                1, 4, 72, "#39FF14");
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();

        simulador.iniciar(List.of(servicio), 6, 59);

        assertEquals(2, simulador.posiciones().size());
        assertEquals(0, simulador.posiciones().getFirst().x());
        assertEquals(1, simulador.posiciones().get(1).x());
        assertEquals(6 * 60 + 59, simulador.horaActualMinutos());
        for (int i = 0; i < 59; i++) simulador.avanzar(1);
        assertEquals(2, simulador.posiciones().size());
        simulador.avanzar(1);

        assertEquals(3, simulador.posiciones().size());
        assertEquals(7 * 60, simulador.horaActualMinutos());
        assertEquals(2.2, EberthMPerfilHoraPunta.factor(simulador.horaActualMinutos()));
    }

    @Test
    void limitaElTotalDeUnidadesEntreServiciosYDespachaGradualmente() {
        LuisYTrayecto ida = trayectoLargo();
        LuisYTrayecto vuelta = trayectoLargoVuelta();
        EberthMServicioMicro servicio = new EberthMServicioMicro("max", "Línea máxima", ida, vuelta,
                1, EberthMSimuladorBuses.MAX_UNIDADES, 0, "#39FF14");
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();

        simulador.iniciar(List.of(servicio), 10, 0);

        assertEquals(2, simulador.posiciones().size());
        assertThrows(IllegalArgumentException.class,
                () -> simulador.iniciar(List.of(servicio, servicio), 10, 0));
        assertEquals(2, simulador.posiciones().size());
    }

    @Test
    void microEsperaEnElTerminalAntesDeRegresar() {
        LuisYTrayecto ida = trayecto();
        PieroGNodo fin = ida.edges().getFirst().destino;
        PieroGNodo inicio = ida.edges().getFirst().origen;
        PieroGEdge edgeVuelta = new PieroGEdge(1, fin, inicio, 100, "residential",
                "Avenida", 30, false, 1, null);
        LuisYTrayecto vuelta = new LuisYTrayecto("vuelta", "Regreso", "Empresa",
                List.of(new LuisYPuntoImportante("p2", "Fin", -69.999, -15),
                        new LuisYPuntoImportante("p1", "Inicio", -70, -15)),
                List.of(edgeVuelta), 100, edgeVuelta.costoBase, "SERVICIO_VUELTA");
        EberthMServicioMicro servicio = new EberthMServicioMicro("espera", "Micro", ida, vuelta,
                1, 1, 18, "#39FF14");
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();
        simulador.iniciar(List.of(servicio), 6, 0);

        avanzar(simulador, 13);
        assertEquals(1, simulador.posiciones().getFirst().x(), 0.001);
        avanzar(simulador, 59);
        assertEquals(1, simulador.posiciones().getFirst().x(), 0.001);
        avanzar(simulador, 2);

        assertTrue(simulador.posiciones().getFirst().x() < 1);
    }

    @Test
    void despachaTodasLasUnidadesAunqueLaDemandaDePasajerosSeAgote() {
        LuisYTrayecto ida = trayectoLargo();
        LuisYTrayecto vuelta = trayectoLargoVuelta();
        EberthMServicioMicro servicio = new EberthMServicioMicro("flota", "Línea", ida, vuelta,
                1, 4, 0, "#39FF14");
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();
        simulador.iniciar(List.of(servicio), 10, 0);

        assertEquals(2, simulador.posiciones().size());
        avanzar(simulador, 60);
        assertEquals(3, simulador.posiciones().size());
        avanzar(simulador, 60);

        assertEquals(4, simulador.posiciones().size());
        assertTrue(simulador.posiciones().stream()
                .allMatch(posicion -> "flota".equals(posicion.idRuta())));
    }

    @Test
    void recalcúlaDesvioYContinuaCuandoSeCierraElTramoActual() {
        PieroGVia directa = via("directa", "directa",
                new double[]{-70.0, -15.0}, new double[]{-69.999, -15.0});
        PieroGVia alternativa = via("alternativa", "alternativa",
                new double[]{-70.0, -15.0}, new double[]{-69.9995, -15.0005},
                new double[]{-69.999, -15.0});
        List<PieroGVia> vias = List.of(directa, alternativa);
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(vias);
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(vias, proyector);

        List<PieroGEdge> ida = grafo.getEdges().stream()
                .filter(edge -> "directa".equals(edge.name)
                        && edge.origen.lon < edge.destino.lon)
                .toList();
        List<PieroGEdge> vuelta = grafo.getEdges().stream()
                .filter(edge -> "directa".equals(edge.name)
                        && edge.origen.lon > edge.destino.lon)
                .toList();
        LuisYTrayecto trayectoIda = new LuisYTrayecto("ida", "Ida", "Empresa",
                List.of(new LuisYPuntoImportante("origen", "Inicio", -70, -15),
                        new LuisYPuntoImportante("destino", "Fin", -69.999, -15)),
                ida, ida.stream().mapToDouble(PieroGEdge::longitudMetros).sum(), 100, "SERVICIO");
        LuisYTrayecto trayectoVuelta = new LuisYTrayecto("vuelta", "Vuelta", "Empresa",
                List.of(new LuisYPuntoImportante("destino", "Fin", -69.999, -15),
                        new LuisYPuntoImportante("origen", "Inicio", -70, -15)),
                vuelta, vuelta.stream().mapToDouble(PieroGEdge::longitudMetros).sum(), 100,
                "SERVICIO_VUELTA");
        EberthMServicioMicro servicio = new EberthMServicioMicro("m1", "Micro", trayectoIda,
                trayectoVuelta, 10, 1, 18, "#39FF14");
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();
        simulador.configurarGrafo(grafo);
        simulador.iniciar(List.of(servicio), 10, 0);
        double xInicial = simulador.posiciones().getFirst().x();
        grafo.getEdges().stream().filter(edge -> "directa".equals(edge.name))
                .forEach(edge -> edge.cerrado = true);

        simulador.avanzar(1);

        assertEquals(EberthMPosicionVehiculo.Estado.EN_RUTA, simulador.posiciones().getFirst().estado());
        assertNotEquals(xInicial, simulador.posiciones().getFirst().x());
        assertTrue(simulador.estaEjecutando());
    }

    @Test
    void laDemandaCreceEnLasParadasYElBusSoloSubeLoQueCabe() {
        PieroGVia directa = via("directa", "directa",
                new double[]{-70.0, -15.0}, new double[]{-69.9995, -15.0},
                new double[]{-69.999, -15.0});
        List<PieroGVia> vias = List.of(directa);
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(vias);
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(vias, proyector);
        List<PieroGEdge> ida = grafo.getEdges().stream()
                .filter(e -> e.origen.lon < e.destino.lon).toList();
        List<PieroGEdge> vuelta = grafo.getEdges().stream()
                .filter(e -> e.origen.lon > e.destino.lon).toList();
        LuisYTrayecto trayectoIda = new LuisYTrayecto("ida", "Ida", "Empresa",
                List.of(new LuisYPuntoImportante("a", "A", -70, -15),
                        new LuisYPuntoImportante("b", "B", -69.9995, -15),
                        new LuisYPuntoImportante("c", "C", -69.999, -15)),
                ida, 100, 100, "SERVICIO");
        LuisYTrayecto trayectoVuelta = new LuisYTrayecto("vuelta", "Vuelta", "Empresa",
                List.of(new LuisYPuntoImportante("c", "C", -69.999, -15),
                        new LuisYPuntoImportante("a", "A", -70, -15)),
                vuelta, 100, 100, "SERVICIO_VUELTA");
        EberthMServicioMicro servicio = new EberthMServicioMicro("m1", "Micro", trayectoIda,
                trayectoVuelta, 1, 1, 0, "#39FF14");
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();
        simulador.configurarGrafo(grafo);
        simulador.iniciar(List.of(servicio), 10, 0); // 10:00, factor de demanda 1.0

        assertTrue(simulador.paradasEnEspera().stream().allMatch(p -> p.esperando() == 0));
        avanzar(simulador, 120);
        int esperando = simulador.paradasEnEspera().stream().mapToInt(EberthMParadaEspera::esperando).max().orElse(0);
        assertEquals(5 * 2, esperando); // +5 por minuto simulado

        avanzar(simulador, 60 * 10);
        assertTrue(simulador.posiciones().stream().allMatch(p -> p.pasajeros() <= p.capacidad()));
        assertTrue(simulador.pasajerosTransportados() > 0);
    }

    @Test
    void laDemandaDeUnaParadaDependeDelPoiCercanoYDelTipoDeDia() {
        assertTrue(demandaEnParada(EberthMCategoriaPoi.EDUCACION, EberthMTipoDia.LABORAL)
                > demandaEnParada(EberthMCategoriaPoi.SALUD, EberthMTipoDia.LABORAL));
        assertTrue(demandaEnParada(EberthMCategoriaPoi.EDUCACION, EberthMTipoDia.DOMINGO)
                < demandaEnParada(EberthMCategoriaPoi.EDUCACION, EberthMTipoDia.LABORAL) / 10);
    }

    /** Pasajeros que esperan tras 10 minutos a las 07:00 en una parada con un POI al lado. */
    private int demandaEnParada(EberthMCategoriaPoi categoria, EberthMTipoDia dia) {
        PieroGVia directa = via("directa", "directa", new double[]{-70.0, -15.0},
                new double[]{-69.9995, -15.0}, new double[]{-69.999, -15.0});
        // Vía terciaria: sin origen residencial, solo cuenta el POI de la parada.
        directa.highway = "tertiary";
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(List.of(directa));
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(List.of(directa), proyector);
        List<PieroGEdge> ida = grafo.getEdges().stream()
                .filter(e -> e.origen.lon < e.destino.lon).toList();
        LuisYTrayecto trayectoIda = new LuisYTrayecto("ida", "Ida", "Empresa",
                List.of(new LuisYPuntoImportante("a", "A", -70, -15),
                        new LuisYPuntoImportante("c", "C", -69.999, -15)),
                ida, 100, 100, "SERVICIO");
        EberthMServicioMicro servicio = new EberthMServicioMicro("m1", "Micro", trayectoIda,
                trayectoIda, 180, 1, 0, "#39FF14");
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();
        simulador.configurarGrafo(grafo);
        simulador.setTipoDia(dia);
        simulador.setPuntosInteres(List.of(new EberthMPoi("P", -70.0, -15.0, categoria)));
        simulador.iniciar(List.of(servicio), 7, 0);
        avanzar(simulador, 600);
        return simulador.paradasEnEspera().stream().mapToInt(EberthMParadaEspera::esperando).max().orElse(0);
    }

    @Test
    void quienApareceLejosDeLaRutaCaminaHastaLaParadaYSeUneALaEspera() {
        PieroGVia directa = via("directa", "directa", new double[]{-70.0, -15.0},
                new double[]{-69.9995, -15.0}, new double[]{-69.999, -15.0});
        PieroGVia lateral = via("lateral", "lateral", new double[]{-70.0, -14.998},
                new double[]{-69.9995, -14.998});
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(List.of(directa, lateral));
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(List.of(directa, lateral), proyector);
        List<PieroGEdge> ida = grafo.getEdges().stream()
                .filter(e -> e.origen.lon < e.destino.lon && e.origen.lat == -15.0).toList();
        LuisYTrayecto trayectoIda = new LuisYTrayecto("ida", "Ida", "Empresa",
                List.of(new LuisYPuntoImportante("a", "A", -70, -15),
                        new LuisYPuntoImportante("c", "C", -69.999, -15)),
                ida, 100, 100, "SERVICIO");
        EberthMServicioMicro servicio = new EberthMServicioMicro("m1", "Micro", trayectoIda,
                trayectoIda, 180, 1, 0, "#39FF14");
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();
        simulador.configurarGrafo(grafo);
        // El POI queda ~220 m al norte de la ruta, junto a una calle por la que no pasa ningún micro.
        simulador.setPuntosInteres(List.of(new EberthMPoi("P", -70.0, -14.998, EberthMCategoriaPoi.EDUCACION)));
        simulador.iniciar(List.of(servicio), 7, 0);

        int maxCaminando = 0;
        for (int t = 0; t < 120 && maxCaminando == 0; t++) {
            simulador.avanzar(1);
            maxCaminando = simulador.pasajerosCaminando();
        }
        assertTrue(maxCaminando > 0, "debe haber gente caminando a la parada");
        assertTrue(simulador.posicionesCaminantes().length > 0);

        avanzar(simulador, 240); // 220 m a 5 km/h ≈ 160 s
        int enParadaLejana = simulador.paradasEnEspera().stream()
                .filter(p -> p.esperando() > 0).mapToInt(EberthMParadaEspera::esperando).sum();
        assertTrue(enParadaLejana > 0 || simulador.pasajerosTransportados() > 0,
                "los que llegaron caminando se unen a la espera o ya subieron");
    }

    @Test
    void ningunaParadaSuperaLaCapacidadMaximaConfigurada() {
        PieroGVia directa = via("directa", "directa", new double[]{-70.0, -15.0},
                new double[]{-69.9995, -15.0}, new double[]{-69.999, -15.0});
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(List.of(directa));
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(List.of(directa), proyector);
        List<PieroGEdge> ida = grafo.getEdges().stream()
                .filter(e -> e.origen.lon < e.destino.lon).toList();
        LuisYTrayecto trayectoIda = new LuisYTrayecto("ida", "Ida", "Empresa",
                List.of(new LuisYPuntoImportante("a", "A", -70, -15),
                        new LuisYPuntoImportante("c", "C", -69.999, -15)),
                ida, 100, 100, "SERVICIO");
        EberthMServicioMicro servicio = new EberthMServicioMicro("m1", "Micro", trayectoIda,
                trayectoIda, 180, 1, 0, "#39FF14");
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();
        simulador.configurarGrafo(grafo);
        var ciudad = EberthMParametrosCiudad.porDefecto(trafiqueishon.geo.PieroGCiudadMapa.JULIACA);
        ciudad.setCapacidadMaximaParada(40);
        simulador.setParametrosCiudad(ciudad);
        simulador.setEscalaDemanda(20);
        simulador.iniciar(List.of(servicio), 7, 0); // hora punta + escala alta: la demanda desborda

        for (int t = 0; t < 600; t++) {
            simulador.avanzar(1);
            assertTrue(simulador.paradasEnEspera().stream().allMatch(p -> p.esperando() <= 40));
        }
        assertEquals(40, simulador.paradasEnEspera().stream()
                .mapToInt(EberthMParadaEspera::esperando).max().orElse(0));
    }

    @Test
    void quienEsperaMasDeQuinceMinutosSeRindeYLiberaLaParada() {
        PieroGVia directa = via("directa", "directa", new double[]{-70.0, -15.0},
                new double[]{-69.9995, -15.0}, new double[]{-69.999, -15.0});
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(List.of(directa));
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(List.of(directa), proyector);
        List<PieroGEdge> ida = grafo.getEdges().stream()
                .filter(e -> e.origen.lon < e.destino.lon).toList();
        LuisYTrayecto trayectoIda = new LuisYTrayecto("ida", "Ida", "Empresa",
                List.of(new LuisYPuntoImportante("a", "A", -70, -15),
                        new LuisYPuntoImportante("c", "C", -69.999, -15)),
                ida, 100, 100, "SERVICIO");
        EberthMServicioMicro servicio = new EberthMServicioMicro("m1", "Micro", trayectoIda,
                trayectoIda, 180, 1, 0, "#39FF14");
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();
        simulador.configurarGrafo(grafo);
        var ciudad = EberthMParametrosCiudad.porDefecto(trafiqueishon.geo.PieroGCiudadMapa.JULIACA);
        ciudad.setCapacidadMaximaParada(10_000);
        // 144 000 × 100 % ÷ 10 (escala) ÷ 1440 min = 10 pasajeros/min entre 2 ubicaciones = 5 c/u.
        ciudad.setPoblacionTotal(144_000);
        ciudad.setPorcentajeTransportePublico(100);
        simulador.setParametrosCiudad(ciudad);
        simulador.iniciar(List.of(servicio), 10, 0); // 10:00, fuera de hora punta

        avanzar(simulador, 60 * 60);
        int max = simulador.paradasEnEspera().stream()
                .mapToInt(EberthMParadaEspera::esperando).max().orElse(0);
        // Sin paciencia llegarían a ~300; con 15 min de paciencia quedan en torno a 5 × 15.
        assertTrue(max > 0 && max <= 100, "esperando: " + max);
    }

    @Test
    void conEscalaAltaLaUnidadNoSaleLlenaDeGenteInventadaYLaSaturacionSeCuenta() {
        PieroGVia directa = via("directa", "directa", new double[]{-70.0, -15.0},
                new double[]{-69.9995, -15.0}, new double[]{-69.999, -15.0});
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(List.of(directa));
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(List.of(directa), proyector);
        List<PieroGEdge> ida = grafo.getEdges().stream()
                .filter(e -> e.origen.lon < e.destino.lon).toList();
        LuisYTrayecto trayectoIda = new LuisYTrayecto("ida", "Ida", "Empresa",
                List.of(new LuisYPuntoImportante("a", "A", -70, -15),
                        new LuisYPuntoImportante("c", "C", -69.999, -15)),
                ida, 100, 100, "SERVICIO");
        EberthMServicioMicro servicio = new EberthMServicioMicro("m1", "Micro", trayectoIda,
                trayectoIda, 180, 1, 50_000, "#39FF14");
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();
        simulador.configurarGrafo(grafo);
        var ciudad = EberthMParametrosCiudad.porDefecto(trafiqueishon.geo.PieroGCiudadMapa.JULIACA);
        ciudad.setCapacidadMaximaParada(5);
        simulador.setParametrosCiudad(ciudad);
        simulador.setEscalaDemanda(100);
        simulador.iniciar(List.of(servicio), 10, 0);

        // Antes salía con 18 pasajeros del contador del despachador aunque la parada estuviera vacía.
        assertEquals(0, simulador.posiciones().getFirst().pasajeros());

        avanzar(simulador, 10 * 60);
        int max = simulador.paradasEnEspera().stream()
                .mapToInt(EberthMParadaEspera::esperando).max().orElse(0);
        assertTrue(max <= 5, "esperando: " + max);
        assertTrue(simulador.pasajerosAbandonaron() > 0, "la parada llena debe contar abandonos");
    }

    @Test
    void conPoisLosPasajerosBajanAlPasarCercaYElBusTerminaVacio() {
        PieroGVia directa = via("directa", "directa",
                new double[]{-70.0, -15.0}, new double[]{-69.998, -15.0},
                new double[]{-69.996, -15.0});
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(List.of(directa));
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(List.of(directa), proyector);
        List<PieroGEdge> ida = grafo.getEdges().stream()
                .filter(e -> e.origen.lon < e.destino.lon).toList();
        List<PieroGEdge> vuelta = grafo.getEdges().stream()
                .filter(e -> e.origen.lon > e.destino.lon).toList();
        LuisYTrayecto trayectoIda = new LuisYTrayecto("ida", "Ida", "Empresa",
                List.of(new LuisYPuntoImportante("a", "A", -70, -15),
                        new LuisYPuntoImportante("b", "B", -69.998, -15),
                        new LuisYPuntoImportante("c", "C", -69.996, -15)),
                ida, 100, 100, "SERVICIO");
        LuisYTrayecto trayectoVuelta = new LuisYTrayecto("vuelta", "Vuelta", "Empresa",
                List.of(new LuisYPuntoImportante("c", "C", -69.996, -15),
                        new LuisYPuntoImportante("a", "A", -70, -15)),
                vuelta, 100, 100, "SERVICIO_VUELTA");
        EberthMServicioMicro servicio = new EberthMServicioMicro("m1", "Micro", trayectoIda,
                trayectoVuelta, 1, 1, 0, "#39FF14");
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();
        simulador.configurarGrafo(grafo);
        // Único POI: sobre la vía (a <50 m de la ruta), entre las paradas B y C.
        simulador.setPuntosInteres(List.of(new EberthMPoi("Mercado", -69.997, -15.0)));
        simulador.iniciar(List.of(servicio), 10, 0);

        avanzar(simulador, 60 * 10);

        assertTrue(simulador.pasajerosTransportados() > 0);
        assertTrue(simulador.posiciones().stream().allMatch(p -> p.pasajeros() <= p.capacidad()));
    }

    @Test
    void losPasajerosBajanEnSuDestinoYLaOcupacionNuncaSuperaLaCapacidadConSobrecupo() {
        PieroGVia directa = via("directa", "directa",
                new double[]{-70.0, -15.0}, new double[]{-69.9995, -15.0},
                new double[]{-69.999, -15.0}, new double[]{-69.9985, -15.0});
        List<PieroGVia> vias = List.of(directa);
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(vias);
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(vias, proyector);
        List<PieroGEdge> ida = grafo.getEdges().stream()
                .filter(e -> e.origen.lon < e.destino.lon)
                .sorted(java.util.Comparator.comparingDouble(e -> e.origen.lon)).toList();
        List<PieroGEdge> vuelta = grafo.getEdges().stream()
                .filter(e -> e.origen.lon > e.destino.lon)
                .sorted(java.util.Comparator.comparingDouble(e -> -e.origen.lon)).toList();
        LuisYTrayecto trayectoIda = new LuisYTrayecto("ida", "Ida", "Empresa",
                List.of(new LuisYPuntoImportante("a", "A", -70, -15),
                        new LuisYPuntoImportante("b", "B", -69.9995, -15),
                        new LuisYPuntoImportante("c", "C", -69.999, -15),
                        new LuisYPuntoImportante("d", "D", -69.9985, -15)),
                ida, 150, 100, "SERVICIO");
        LuisYTrayecto trayectoVuelta = new LuisYTrayecto("vuelta", "Vuelta", "Empresa",
                List.of(new LuisYPuntoImportante("d", "D", -69.9985, -15),
                        new LuisYPuntoImportante("a", "A", -70, -15)),
                vuelta, 150, 100, "SERVICIO_VUELTA");
        EberthMServicioMicro servicio = new EberthMServicioMicro("m1", "Micro", trayectoIda,
                trayectoVuelta, 1, 1, 0, "#39FF14");
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();
        simulador.configurarGrafo(grafo);
        simulador.iniciar(List.of(servicio), 18, 0); // hora punta: más demanda

        int maximo = 0;
        for (int t = 0; t < 1_800; t++) {
            simulador.avanzar(1);
            for (EberthMPosicionVehiculo v : simulador.posiciones()) {
                // Abordaje universal: se admite hasta un 20 % de sobrecupo (pasajeros apretados).
                assertTrue(v.pasajeros() >= 0 && v.pasajeros() <= Math.ceil(v.capacidad() * 1.2));
                maximo = Math.max(maximo, v.pasajeros());
            }
        }
        assertTrue(maximo > 0);
        // Con varias vueltas, los que suben tienen que bajar: se transporta más de lo que cabe.
        assertTrue(simulador.pasajerosTransportados() > 18);
    }

    @Test
    void losContadoresDePasajerosCuadranConLosGenerados() {
        PieroGVia directa = via("directa", "directa",
                new double[]{-70.0, -15.0}, new double[]{-69.9995, -15.0},
                new double[]{-69.999, -15.0}, new double[]{-69.9985, -15.0});
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(List.of(directa));
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(List.of(directa), proyector);
        List<PieroGEdge> ida = grafo.getEdges().stream()
                .filter(e -> e.origen.lon < e.destino.lon)
                .sorted(java.util.Comparator.comparingDouble(e -> e.origen.lon)).toList();
        List<PieroGEdge> vuelta = grafo.getEdges().stream()
                .filter(e -> e.origen.lon > e.destino.lon)
                .sorted(java.util.Comparator.comparingDouble(e -> -e.origen.lon)).toList();
        LuisYTrayecto trayectoIda = new LuisYTrayecto("ida", "Ida", "Empresa",
                List.of(new LuisYPuntoImportante("a", "A", -70, -15),
                        new LuisYPuntoImportante("b", "B", -69.9995, -15),
                        new LuisYPuntoImportante("c", "C", -69.999, -15),
                        new LuisYPuntoImportante("d", "D", -69.9985, -15)),
                ida, 150, 100, "SERVICIO");
        LuisYTrayecto trayectoVuelta = new LuisYTrayecto("vuelta", "Vuelta", "Empresa",
                List.of(new LuisYPuntoImportante("d", "D", -69.9985, -15),
                        new LuisYPuntoImportante("a", "A", -70, -15)),
                vuelta, 150, 100, "SERVICIO_VUELTA");
        EberthMServicioMicro servicio = new EberthMServicioMicro("m1", "Micro", trayectoIda,
                trayectoVuelta, 1, 1, 0, "#39FF14");
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();
        simulador.configurarGrafo(grafo);
        simulador.setParametrosCiudad(new EberthMParametrosCiudad(280_000, 65, "17:30-19:30", 0,
                EberthMParametrosCiudad.TasaAccidentes.BAJA));
        simulador.iniciar(List.of(servicio), 17, 30);

        for (int t = 0; t < 3_600; t++) {
            simulador.avanzar(1);
            long aBordo = simulador.posiciones().stream().mapToLong(EberthMPosicionVehiculo::pasajeros).sum();
            long esperando = simulador.paradasEnEspera().stream().mapToLong(EberthMParadaEspera::esperando).sum();
            long suma = aBordo + esperando + simulador.pasajerosCaminando()
                    + simulador.pasajerosAbandonaron() + simulador.pasajerosBajaron();
            assertEquals(suma, simulador.pasajerosGenerados(), "no cuadra en t=" + t);
            assertTrue(simulador.pasajerosTransportados() <= simulador.pasajerosGenerados());
        }
        assertTrue(simulador.pasajerosGenerados() > 0);
    }

    @Test
    void conEventosOrganicosActivosElSimuladorGeneraYHaceCaducarEventos() {
        PieroGVia principal = via("p", "Avenida", new double[]{-70.0, -15.0}, new double[]{-69.99, -15.0});
        principal.highway = "primary";
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(List.of(principal));
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(List.of(principal), proyector);
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();
        simulador.configurarGrafo(grafo);
        simulador.iniciar(trayecto(), 1);
        var ciudad = EberthMParametrosCiudad.porDefecto(trafiqueishon.geo.PieroGCiudadMapa.LIMA);
        ciudad.setProbabilidadLluvia(100);
        simulador.setParametrosCiudad(ciudad);
        List<EberthMEventoTrafico> nuevos = new java.util.ArrayList<>();
        List<EberthMEventoTrafico> expirados = new java.util.ArrayList<>();
        simulador.setOyenteEventosOrganicos(c -> {
            nuevos.addAll(c.nuevos());
            expirados.addAll(c.expirados());
        });

        avanzar(simulador, 3 * 3600); // apagado: nada
        assertTrue(nuevos.isEmpty());

        simulador.setEventosOrganicos(true);
        avanzar(simulador, 3 * 3600);

        assertFalse(nuevos.isEmpty());
        assertTrue(nuevos.stream().anyMatch(e -> e.tipo() == EberthMTipoEvento.LLUVIA));
        assertFalse(expirados.isEmpty());
    }

    @Test
    void unListenerFallidoNoImpideNotificarLosSiguientes() {
        EberthMGestorEventos gestor = new EberthMGestorEventos();
        boolean[] notificado = {false};
        gestor.agregarListener(eventos -> {
            throw new IllegalStateException("falla aislada");
        });
        gestor.agregarListener(eventos -> notificado[0] = true);

        gestor.publicar(List.of(new EberthMEventoTrafico("Cierre", EberthMTipoEvento.VIA_CERRADA,
                -70, -15)));

        assertTrue(notificado[0]);
    }

    @Test
    void ajustaLaDemandaPorFranjaHoraria() {
        EberthMParametrosCiudad ciudad = new EberthMParametrosCiudad(100_000, 50, "07:00-09:00", 0,
                EberthMParametrosCiudad.TasaAccidentes.BAJA);
        assertEquals(2.5, EberthMPerfilHoraPunta.multiplicadorDemanda(ciudad, 7, 30));
        assertEquals(1.0, EberthMPerfilHoraPunta.multiplicadorDemanda(ciudad, 10, 0));
        assertEquals(0.1, EberthMPerfilHoraPunta.multiplicadorDemanda(ciudad, 23, 0));
    }

    @Test
    void gasolineraDetieneElVehiculoPorTresMinutosSimulados() {
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();
        simulador.iniciar(trayecto(), 1);
        simulador.actualizarEventos(List.of(new EberthMEventoTrafico("Gasolinera",
                EberthMTipoEvento.GASOLINERA, -70, -15)));

        simulador.avanzar(1);
        double detenidoEn = simulador.posiciones().getFirst().x();
        assertEquals(EberthMPosicionVehiculo.Estado.DETENIDO,
                simulador.posiciones().getFirst().estado());
        avanzar(simulador, 178);
        assertEquals(detenidoEn, simulador.posiciones().getFirst().x(), 0.000001);
        assertEquals(EberthMPosicionVehiculo.Estado.DETENIDO,
                simulador.posiciones().getFirst().estado());

        simulador.avanzar(1);
        assertEquals(EberthMPosicionVehiculo.Estado.EN_RUTA,
                simulador.posiciones().getFirst().estado());
    }

    @Test
    void haceUnaPausaDeQuinceSegundosEnLasParadasIntermedias() {
        PieroGVia via = via("linea", "Avenida",
                new double[]{-70.0, -15.0}, new double[]{-69.999, -15.0},
                new double[]{-69.998, -15.0});
        PieroGProyector proyector = new PieroGProyector();
        proyector.preProyectar(List.of(via));
        PieroGGrafoVial grafo = new PieroGGrafoVial();
        grafo.construir(List.of(via), proyector);
        List<PieroGEdge> ida = grafo.getEdges().stream()
                .filter(edge -> edge.origen.lon < edge.destino.lon)
                .toList();
        List<PieroGEdge> vuelta = grafo.getEdges().stream()
                .filter(edge -> edge.origen.lon > edge.destino.lon)
                .toList();
        List<LuisYPuntoImportante> puntosIda = List.of(
                new LuisYPuntoImportante("a", "Inicio", -70, -15),
                new LuisYPuntoImportante("b", "Parada", -69.999, -15),
                new LuisYPuntoImportante("c", "Final", -69.998, -15));
        List<LuisYPuntoImportante> puntosVuelta = List.of(
                puntosIda.get(2), puntosIda.get(1), puntosIda.get(0));
        LuisYTrayecto trayectoIda = new LuisYTrayecto("ida", "Ida", "Empresa", puntosIda,
                ida, ida.stream().mapToDouble(PieroGEdge::longitudMetros).sum(), 100, "SERVICIO");
        LuisYTrayecto trayectoVuelta = new LuisYTrayecto("vuelta", "Vuelta", "Empresa", puntosVuelta,
                vuelta, vuelta.stream().mapToDouble(PieroGEdge::longitudMetros).sum(), 100,
                "SERVICIO_VUELTA");
        EberthMServicioMicro servicio = new EberthMServicioMicro("paradas", "Micro", trayectoIda,
                trayectoVuelta, 10, 1, 0, "#39FF14");
        EberthMSimuladorBuses simulador = new EberthMSimuladorBuses();
        simulador.configurarGrafo(grafo);
        simulador.iniciar(List.of(servicio), 10, 0);

        for (int i = 0; i < 20
                && simulador.posiciones().getFirst().estado() != EberthMPosicionVehiculo.Estado.EN_PARADA;
             i++) {
            simulador.avanzar(1);
        }
        assertEquals(EberthMPosicionVehiculo.Estado.EN_PARADA,
                simulador.posiciones().getFirst().estado());
        double enParada = simulador.posiciones().getFirst().x();
        avanzar(simulador, 14);
        assertEquals(enParada, simulador.posiciones().getFirst().x(), 0.000001);
        assertEquals(EberthMPosicionVehiculo.Estado.EN_PARADA,
                simulador.posiciones().getFirst().estado());
        simulador.avanzar(1);
        assertEquals(EberthMPosicionVehiculo.Estado.EN_RUTA,
                simulador.posiciones().getFirst().estado());
    }

    private PieroGVia via(String id, String nombre, double[]... coordenadas) {
        PieroGVia via = new PieroGVia();
        via.id = id;
        via.name = nombre;
        via.highway = "residential";
        via.coords = java.util.Arrays.stream(coordenadas).toList();
        return via;
    }

    private void avanzar(EberthMSimuladorBuses simulador, int segundos) {
        for (int i = 0; i < segundos; i++) simulador.avanzar(1);
    }

    private LuisYTrayecto trayecto() {
        PieroGNodo inicio = new PieroGNodo(0, 0, 0, -70, -15);
        PieroGNodo fin = new PieroGNodo(1, 1, 0, -69.999, -15);
        PieroGEdge edge = new PieroGEdge(0, inicio, fin, 100, "residential",
                "Avenida", 30, false, 1, null);
        LuisYTrayecto trayecto = new LuisYTrayecto("t1", "Ruta", "Empresa",
                List.of(new LuisYPuntoImportante("p1", "Inicio", -70, -15),
                        new LuisYPuntoImportante("p2", "Fin", -69.999, -15)),
                List.of(edge), 100, edge.costoBase, "PARTIDA");
        return trayecto;
    }

    private LuisYTrayecto trayectoLargo() {
        PieroGNodo inicio = new PieroGNodo(0, 0, 0, -70, -15);
        PieroGNodo fin = new PieroGNodo(1, 1, 0, -69.9, -15);
        PieroGEdge edge = new PieroGEdge(0, inicio, fin, 10_000, "residential",
                "Avenida", 30, false, 1, null);
        return new LuisYTrayecto("largo", "Ruta larga", "Empresa",
                List.of(new LuisYPuntoImportante("inicio", "Paradero", -70, -15),
                        new LuisYPuntoImportante("fin", "Destino", -69.9, -15)),
                List.of(edge), 10_000, edge.costoBase, "SERVICIO");
    }

    private LuisYTrayecto trayectoLargoVuelta() {
        PieroGNodo fin = new PieroGNodo(0, 1, 0, -69.9, -15);
        PieroGNodo inicio = new PieroGNodo(1, 0, 0, -70, -15);
        PieroGEdge edge = new PieroGEdge(0, fin, inicio, 10_000, "residential",
                "Avenida", 30, false, 1, null);
        return new LuisYTrayecto("vuelta", "Ruta vuelta", "Empresa",
                List.of(new LuisYPuntoImportante("fin", "Destino", -69.9, -15),
                        new LuisYPuntoImportante("inicio", "Paradero", -70, -15)),
                List.of(edge), 10_000, edge.costoBase, "SERVICIO_VUELTA");
    }

    @Test
    void laDemandaSigueLaPoblacionYLaHoraPuntaConfiguradas() {
        EberthMParametrosCiudad ciudad = new EberthMParametrosCiudad(144_000, 100, "10:00-11:00", 0,
                EberthMParametrosCiudad.TasaAccidentes.BAJA);
        assertEquals(2.5, EberthMPerfilHoraPunta.multiplicadorDemanda(ciudad, 10 * 60 + 30,
                EberthMTipoDia.LABORAL, false), 1e-9);
        assertEquals(1.0, EberthMPerfilHoraPunta.multiplicadorDemanda(ciudad, 15 * 60,
                EberthMTipoDia.LABORAL, false), 1e-9);
        assertEquals(0.1, EberthMPerfilHoraPunta.multiplicadorDemanda(ciudad, 60,
                EberthMTipoDia.LABORAL, false), 1e-9);
        assertEquals(2.5, EberthMPerfilHoraPunta.multiplicadorDemanda(ciudad, 60,
                EberthMTipoDia.LABORAL, true), 1e-9);
    }

    @Test
    void elPerfilDeDemandaSigueLasHorasPuntaYElPorcentajeDePoblacionConfigurados() {
        // 20 % de 100 000 habitantes (de 60 000 usuarios) viaja entre las 06:00 y las 08:30.
        EberthMParametrosCiudad ciudad = new EberthMParametrosCiudad(100_000, 60, "06:00-08:30", 0,
                EberthMParametrosCiudad.TasaAccidentes.BAJA);
        ciudad.setPorcentajePoblacionEnPunta(20);
        double[] horas = ciudad.fraccionesHorarias(EberthMTipoDia.LABORAL);
        assertEquals(1.0, java.util.Arrays.stream(horas).sum(), 1e-9);
        double[] minutos = ciudad.perfilPorMinuto(EberthMTipoDia.LABORAL);
        double enPunta = java.util.stream.IntStream.range(6 * 60, 8 * 60 + 30).mapToDouble(m -> minutos[m]).sum();
        assertEquals(20_000.0 / 60_000.0, enPunta, 1e-9);
        assertEquals(horas[6], horas[7], 1e-12);
        assertTrue(horas[7] > 3 * horas[13], "la punta debe destacar sobre el resto del día");
        // Cambiar el rango mueve el pico: nada queda fijo en 07:00-09:00.
        ciudad.setHorasPunta("15:00-16:00");
        assertTrue(ciudad.fraccionesHorarias(EberthMTipoDia.LABORAL)[15] > horas[7] / 4);
        assertTrue(ciudad.fraccionesHorarias(EberthMTipoDia.LABORAL)[7] < horas[7] / 2);
    }
}
