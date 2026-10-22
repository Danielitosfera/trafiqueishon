package trafiqueishon.ui;

import javafx.application.Platform;
import javafx.animation.AnimationTimer;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;
import trafiqueishon.geo.*;
import trafiqueishon.io.BetoHFuenteMapa;
import trafiqueishon.ruta.LuisYPaletaColores;
import trafiqueishon.ruta.LuisYPuntoImportante;
import trafiqueishon.ruta.LuisYTrayecto;
import trafiqueishon.simulacion.EberthMEventoTrafico;
import trafiqueishon.simulacion.EberthMMotorSimulacion;
import trafiqueishon.simulacion.EberthMPosicionVehiculo;
import trafiqueishon.simulacion.EberthMSemaforo;
import trafiqueishon.simulacion.EberthMServicioMicro;
import trafiqueishon.simulacion.EberthMParadaEspera;
import trafiqueishon.simulacion.EberthMTipoEvento;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mapa vial con dos capas de {@link Canvas}:
 * <ul>
 *   <li><b>capaVias</b>: calles, nombres y zonas. Solo se repinta al cambiar
 *       zoom/tamaño o al terminar un arrastre; durante el arrastre se desplaza
 *       la imagen ya pintada (paneo instantáneo aun en Lima).</li>
 *   <li><b>canvas</b>: rutas, puntos, eventos y vehículos. Lo pinta un
 *       {@link AnimationTimer} (hasta 30 FPS) leyendo la última foto del motor
 *       de simulación; nunca se repinta por cada evento individual.</li>
 * </ul>
 * Todo se recorta al área visible (viewport culling): las calles por STRtree,
 * y rutas, puntos, eventos y vehículos por caja de pantalla.
 */
public class LuisYMapaRutasCanvas extends Pane {
    private static final Logger LOG = Logger.getLogger(LuisYMapaRutasCanvas.class.getName());

    /** Presupuesto de fotograma: 30 FPS como máximo (se saltan los fotogramas que llegan antes). */
    private static final long INTERVALO_MINIMO_NANOS = 1_000_000_000L / 30;
    /** Con más micros que esto en pantalla el mapa baja a 20 FPS. */
    private static final int MICROS_PARA_BAJAR_FPS = 500;
    private static final long INTERVALO_MUCHOS_MICROS_NANOS = 1_000_000_000L / 20;

    // ===== Colores =====
    private Color fondo = Color.web("#181825");
    private Color colorTitulo = Color.web("#89B4FA");
    private static final Color COLOR_IDA = Color.web(LuisYPaletaColores.COLOR_RUTA_IDA);
    private static final Color COLOR_VUELTA = Color.web(LuisYPaletaColores.COLOR_RUTA_VUELTA);
    private static final Color COLOR_RUTA_ACTIVA = Color.web(LuisYPaletaColores.COLOR_RUTA_ACTIVA);
    private Color colorPuntoBorde = Color.web("#1E1E2E");
    private Color colorPuntoTexto = Color.web("#CDD6F4");
    private Color colorHalo = Color.web("#11111B", 0.85);
    private Color colorCluster = Color.web("#89B4FA");
    private static final Color COLOR_INICIO_RUTA = Color.web("#39FF14");
    private static final Color COLOR_FINAL_RUTA = Color.web("#FF3B5C");
    private static final Color COLOR_EXTREMO_COMPARTIDO = Color.web("#FFD740");

    /**
     * Desplazamiento lateral (px) de cada trayecto, medido a la derecha de su propio
     * sentido de marcha. Ida y vuelta circulan en sentidos opuestos, así que con el mismo
     * signo quedan en lados distintos de la calle, como una autopista de dos carriles.
     */
    private static final double DESFASE_CARRIL = 3.0;
    /** Desplazamiento lateral (px) de cada vehículo a la derecha de su sentido de marcha. */
    private static final double DESFASE_CARRIL_VEHICULO = 4.0;
    private static final Font FONT_PUNTO = Font.font("System", 12);
    private static final Font FONT_PUNTO_EXTREMO = Font.font("System", FontWeight.BOLD, 12);
    private static final Font FONT_CLUSTER = Font.font("System", FontWeight.BOLD, 12);
    private static final Font FONT_VEHICULO = Font.font("System", 11);
    private static final Font FONT_MENSAJE = Font.font("System", FontWeight.BOLD, 16);

    private static final Color FONDO_OSCURO = Color.web("#181825");
    private static final Color[] COLORES_VIAS_OSCURO = {
            Color.web("#9399B2"), Color.web("#7F849C"), Color.web("#6C7086"),
            Color.web("#585B70"), Color.web("#45475A"), Color.web("#45475A"),
            Color.web("#3B3D4F"), Color.web("#4A4538"), Color.web("#585B70")
    };
    private static final Color[] COLORES_VIAS_CLARO = {
            Color.web("#B44B61"), Color.web("#C47B28"), Color.web("#9C8C15"),
            Color.web("#4C596D"), Color.web("#A0A7B4"), Color.web("#B7BEC9"),
            Color.web("#C3C9D2"), Color.web("#A58D72"), Color.web("#B7BEC9")
    };
    private static final int TAMANO_CELDA_VEHICULOS = 18;

    // ===== LOD =====
    /** Margen de seguridad (fracción del lado) pintado fuera de pantalla en la capa de vías. */
    private static final double MARGEN_VIEWPORT = 0.20;
    /** Por debajo de este zoom: sin POIs, sin semáforos, vías finas y vehículos de 2x2 px. */
    private static final double ZOOM_LEJANO = 5.0;
    /** Por encima de este zoom: nombres de calles y vehículos con su forma real. */
    private static final double ZOOM_CERCANO = 15.0;
    /** Los círculos de «zonas sin cobertura» solo se dibujan con zoom mayor; antes basta el contador. */
    private static final double ZOOM_ZONAS_SIN_COBERTURA = 10.0;
    private static final Color COLOR_GENTE_DEMANDA = Color.STEELBLUE.deriveColor(0, 1, 1, 0.75);
    /** Zoom a partir del cual se rotulan los puntos en modo automático. */
    private static final double ZOOM_ETIQUETAS_PUNTOS = 10.0;
    /** Por debajo de este zoom no se dibujan clústeres ni puntos sueltos: mapa limpio. */
    private static final double ZOOM_MAPA_LIMPIO = 10.0;
    /** Margen (fracción del lado) fuera de pantalla dentro del cual aún se dibujan los vehículos. */
    private static final double MARGEN_CULLING = 0.10;
    /** Grosor de cada calle (y de cada línea de una avenida de doble vía), en píxeles. */
    private static final double ANCHO_VIA_PX = 1.5;
    /** Separación entre las dos líneas de una avenida, en píxeles. */
    private static final double ANCHO_HUECO_AVENIDA_PX = 2.5;
    /** Lado de la celda de agrupación de puntos, en píxeles de pantalla. */
    private static final double CELDA_CLUSTER = 38;
    /** Por encima de este zoom ya no se agrupan puntos. */
    private static final double ZOOM_SIN_CLUSTER = 48;
    /** Mapas con más vías que esto ocultan calles secundarias con zoom lejano. */
    private static final int UMBRAL_MAPA_GRANDE = 60_000;
    private static final double ZOOM_DETALLE_MAPA_GRANDE = 4.0;
    /** Si aun así quedan más vías visibles, se omiten las secundarias. */
    private static final int MAX_VIAS_DETALLE = 80_000;

    /** Cómo se rotulan los puntos de interés. */
    public enum EtiquetasPuntos {
        AUTOMATICO("Automático (según zoom)"), SIEMPRE("Siempre"), OCULTAS("Ocultas");

        private final String texto;

        EtiquetasPuntos(String texto) { this.texto = texto; }

        @Override public String toString() { return texto; }
    }

    // ===== Croquis =====
    private static final Color CROQUIS_CALLE = Color.web("#8A8A8A");
    private static final Color CROQUIS_TRAMADO = Color.web("#BDBDBD");
    private static final int CELDAS_URBANAS = 48;
    private static final double UMBRAL_URBANO = 0.12;
    private static final Font FONT_LEYENDA = Font.font("System", FontWeight.NORMAL, 12);
    private static final Font FONT_MARCA_AGUA = Font.font("System", FontWeight.BOLD, 16);
    private static final Color FONDO_LEYENDA = Color.web("#11111B", 0.85);
    private static final Color TEXTO_LEYENDA = Color.web("#E6EAF5");
    private static final double ALTO_LEYENDA = 28;
    private static final Text MEDIDOR = new Text();

    /** Zona rotulada en el croquis (posición geográfica del centro del nombre). */
    public record ZonaCroquis(String nombre, double lon, double lat) {}

    private record DensidadUrbana(boolean[] celdas, double cx, double cy) {}

    private static final double[] ZOOMS = {
            0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0,
            12.0, 16.0, 24.0, 32.0, 48.0, 72.0, 108.0, 162.0, 243.0,
            364.0, 546.0, 729.0
    };

    private final Canvas capaVias;
    private final Canvas canvas;
    private final PieroGGeoJsonParser parser = new PieroGGeoJsonParser();
    private final BetoHFuenteMapa fuenteMapa;
    private final AtomicLong generacionCarga = new AtomicLong();
    private PieroGCiudadMapa ciudad = PieroGCiudadMapa.JULIACA;
    private String mensajeCarga = "Cargando red vial...";

    private PieroGProyector proyector = new PieroGProyector();
    private List<PieroGVia> vias;
    private STRtree indiceVias;
    private STRtree indiceViasPrincipales;
    private PieroGGrafoVial grafo;
    private double segundosSimulados;
    private List<PieroGEdge> viasCerradas = List.of();
    private List<PieroGEdge> viasTrancadera = List.of();
    private Color[] coloresVias = COLORES_VIAS_OSCURO;
    private boolean temaClaro;
    private boolean modoCroquis = true;
    private boolean[] zonaUrbana;
    private List<ZonaCroquis> zonas = List.of();
    private boolean zonasDefinidas;
    private int columnasVehiculos;
    private int[] cantidadVehiculosPorCelda = new int[0];
    private Color[] colorVehiculosPorCelda = new Color[0];
    private boolean cargado = false;

    private final List<List<PieroGVia>> gruposVias = crearGruposVias();
    private final List<LuisYPuntoImportante> puntos = new ArrayList<>();
    private Set<String> puntosInicioRutas = Set.of();
    private Set<String> puntosFinalRutas = Set.of();
    private List<EberthMEventoTrafico> eventos = List.of();
    private boolean hayLluvia;
    /** Hay accidentes o trancaderas: sus animaciones piden fotogramas nuevos. */
    private boolean hayAnimacionEventos;
    private List<EberthMPosicionVehiculo> vehiculos = List.of();
    private List<EberthMParadaEspera> paradasEnEspera = List.of();
    /** {@code lon, lat} de la gente de la demanda generada (sin rutas); vacío si no hay demanda. */
    private double[] genteDemanda = new double[0];
    /** {@code x, y, transbordo} de cada grupo que camina hacia una parada. */
    private double[] caminantes = new double[0];
    /** {@code x, y, pasajeros} de cada zona sin cobertura. */
    private double[] zonasSinCobertura = new double[0];
    private List<EberthMServicioMicro> rutasMicros = List.of();
    private Set<String> rutasMicrosVisibles = Set.of();
    private EberthMServicioMicro microIdaVuelta;
    private boolean mostrarCalor;
    private boolean mostrarIda = true;
    private boolean mostrarVuelta = true;
    private LuisYTrayecto trayectoPartida;
    private LuisYTrayecto trayectoRetorno;
    private EtiquetasPuntos etiquetasPuntos = EtiquetasPuntos.AUTOMATICO;
    private final Map<String, Double> anchosTexto = new HashMap<>();
    private final Map<String, Color> cacheColores = new HashMap<>();

    private double offsetX = 0, offsetY = 0;
    private double zoom = 1.0;
    private double escalaBase = 1.0;
    private int nivelZoom = 2;
    private double anchoAnterior, altoAnterior;

    // Estado con el que se pintó la capa de vías (paneo por desplazamiento).
    private double offsetXVias, offsetYVias;
    private boolean arrastrando;

    private double lastMouseX, lastMouseY;
    private boolean necesitaRedibujar = true;
    private boolean viasNecesitanRedibujarse = true;
    private long ultimoRenderNanos;
    private long ultimaDuracionRender;
    /** Micros dentro de la pantalla en el último fotograma (decide los FPS del siguiente). */
    private int vehiculosVisibles;
    private Supplier<EberthMMotorSimulacion.Instantanea> fuenteVehiculos;
    private long versionVehiculos = -1;

    private Runnable onMapaCargado;
    private java.util.function.Consumer<String> onError;
    private java.util.function.Consumer<String> onInfoMapa;
    private String ultimaInfoMapa;
    private java.util.function.BiConsumer<Double, Double> onClic;
    private java.util.function.Predicate<double[]> onDobleClic;

    private final AnimationTimer renderizador = new AnimationTimer() {
        @Override
        public void handle(long ahora) {
            try {
                leerFotoSimulacion();
                // La lluvia está animada: pide un fotograma nuevo mientras haya alguna activa.
                if (cargado && (hayLluvia || hayAnimacionEventos)) necesitaRedibujar = true;
                if (!necesitaRedibujar && !viasNecesitanRedibujarse) return;
                // Presupuesto adaptativo: si un fotograma tarda más de 33 ms
                // (p. ej. Lima con miles de vehículos) se espacian los siguientes
                // en lugar de saturar el hilo de JavaFX.
                long base = vehiculosVisibles > MICROS_PARA_BAJAR_FPS
                        ? INTERVALO_MUCHOS_MICROS_NANOS : INTERVALO_MINIMO_NANOS;
                long intervalo = Math.max(base, ultimaDuracionRender * 5 / 4);
                if (ahora - ultimoRenderNanos < intervalo) return;
                necesitaRedibujar = false;
                ultimoRenderNanos = ahora;
                long inicio = System.nanoTime();
                renderizar();
                ultimaDuracionRender = System.nanoTime() - inicio;
            } catch (RuntimeException ex) {
                LOG.log(Level.SEVERE, "Se omitió un fotograma por un error del mapa", ex);
            }
        }
    };

    public LuisYMapaRutasCanvas() {
        this(new BetoHFuenteMapa());
    }

    public LuisYMapaRutasCanvas(BetoHFuenteMapa fuenteMapa) {
        this.fuenteMapa = fuenteMapa;
        capaVias = new Canvas();
        canvas = new Canvas();
        getChildren().addAll(capaVias, canvas);
        // La capa de vías es un 20% más grande por cada lado que la pantalla: al arrastrar
        // o hacer zoom ya hay calles pintadas justo fuera del borde visible.
        capaVias.widthProperty().bind(widthProperty().multiply(1 + 2 * MARGEN_VIEWPORT));
        capaVias.heightProperty().bind(heightProperty().multiply(1 + 2 * MARGEN_VIEWPORT));
        capaVias.layoutXProperty().bind(widthProperty().multiply(-MARGEN_VIEWPORT));
        capaVias.layoutYProperty().bind(heightProperty().multiply(-MARGEN_VIEWPORT));
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        // Recorte: al arrastrar, la capa de calles desplazada no debe pintarse
        // sobre el panel lateral ni la barra superior.
        Rectangle recorte = new Rectangle();
        recorte.widthProperty().bind(widthProperty());
        recorte.heightProperty().bind(heightProperty());
        setClip(recorte);
        widthProperty().addListener((o, a, b) -> recalcularConservandoCentro());
        heightProperty().addListener((o, a, b) -> recalcularConservandoCentro());

        setOnScroll(e -> {
            if (!cargado) return;
            double delta = e.getDeltaY();
            if (Math.abs(delta) < 0.5) return;
            int nuevoNivel = Math.clamp(nivelZoom + (delta > 0 ? 1 : -1), 0, ZOOMS.length - 1);
            if (nuevoNivel == nivelZoom) return;
            aplicarNivelZoom(nuevoNivel, e.getX(), e.getY());
        });

        setOnMousePressed(e -> { lastMouseX = e.getX(); lastMouseY = e.getY(); });
        setOnMouseDragged(e -> {
            if (!cargado) return;
            arrastrando = true;
            offsetX += e.getX() - lastMouseX;
            offsetY += e.getY() - lastMouseY;
            lastMouseX = e.getX();
            lastMouseY = e.getY();
            desplazarCapaVias();
        });
        setOnMouseReleased(e -> {
            if (arrastrando) {
                arrastrando = false;
                redibujarMapa();
            }
        });

        setOnMouseClicked(e -> {
            if (!cargado || !e.isStillSincePress()) return;
            if (e.getClickCount() == 2) {
                // Si la ventana activa usa el doble clic (colocar un evento), no se reencuadra.
                if (onDobleClic != null && onDobleClic.test(pantallaALonLat(e.getX(), e.getY()))) return;
                resetVista();
                return;
            }
            if (onClic != null) {
                double[] norm = pantallaANormalizado(e.getX(), e.getY());
                double[] lonlat = normalizadoALonLat(norm[0], norm[1]);
                onClic.accept(lonlat[0], lonlat[1]);
            }
        });

        aplicarEstiloBase();
        renderizador.start();
    }

    // ==================================================================
    //  CARGA POR CIUDAD
    // ==================================================================

    /**
     * Carga en segundo plano las calles de la ciudad. Si se pide otra ciudad
     * antes de terminar, el resultado anterior se descarta.
     */
    public void cargarCiudad(PieroGCiudadMapa nueva) {
        long generacion = generacionCarga.incrementAndGet();
        ciudad = nueva;
        cargado = false;
        vias = null;
        indiceVias = null;
        indiceViasPrincipales = null;
        grafo = null;
        zonaUrbana = null;
        viasCerradas = List.of();
        viasTrancadera = List.of();
        puntos.clear();
        eventos = List.of();
        vehiculos = List.of();
        paradasEnEspera = List.of();
        caminantes = new double[0];
        zonasSinCobertura = new double[0];
        rutasMicros = List.of();
        rutasMicrosVisibles = Set.of();
        microIdaVuelta = null;
        trayectoPartida = null;
        trayectoRetorno = null;
        anchosTexto.clear();
        if (!zonasDefinidas) zonas = List.of();
        mensajeCarga = "Cargando mapa de " + nueva.nombre() + "...";
        redibujarMapa();

        Thread hilo = new Thread(() -> cargarEnSegundoPlano(nueva, generacion),
                "cargador-mapa-" + nueva.id());
        hilo.setDaemon(true);
        hilo.start();
    }

    private void cargarEnSegundoPlano(PieroGCiudadMapa destino, long generacion) {
        try {
            List<PieroGVia> cargadas;
            try (InputStream entrada = fuenteMapa.abrir(destino, BetoHFuenteMapa.CALLES)) {
                cargadas = parser.parsear(entrada);
            }
            if (generacion != generacionCarga.get()) return;
            if (cargadas.isEmpty()) {
                throw new IllegalStateException("El GeoJSON de " + destino.nombre()
                        + " no contiene calles LineString.");
            }
            PieroGProyector p = new PieroGProyector();
            p.preProyectar(cargadas);

            STRtree indice = new STRtree();
            STRtree principales = new STRtree();
            for (PieroGVia via : cargadas) {
                if (via.highway == null || via.projX == null || via.projX.length < 2) continue;
                Envelope caja = new Envelope(via.minX, via.maxX, via.minY, via.maxY);
                indice.insert(caja, via);
                if (esPrincipal(via.highway)) principales.insert(caja, via);
            }
            indice.build();
            principales.build();

            DensidadUrbana densidad = calcularDensidadUrbana(cargadas);
            ZonaCroquis zonaPrincipal = new ZonaCroquis(destino.nombre().toUpperCase(Locale.ROOT),
                    p.getMinLon() + densidad.cx() * p.getAnchoGeo(),
                    p.getMaxLat() - densidad.cy() * p.getAltoGeo());
            PieroGGrafoVial g = new PieroGGrafoVial();
            g.construir(cargadas, p);
            for (PieroGVia v : cargadas) v.liberarCoords();
            if (generacion != generacionCarga.get()) return;

            Platform.runLater(() -> {
                if (generacion != generacionCarga.get()) return;
                vias = cargadas;
                indiceVias = indice;
                indiceViasPrincipales = principales;
                grafo = g;
                proyector = p;
                zonaUrbana = densidad.celdas();
                if (!zonasDefinidas) zonas = List.of(zonaPrincipal);
                cargado = true;
                viasCerradas = List.of();
                viasTrancadera = List.of();
                recalcularEscalaBase();
                resetVista();
                if (onMapaCargado != null) onMapaCargado.run();
            });
        } catch (Exception | OutOfMemoryError ex) {
            LOG.log(Level.SEVERE, "No se pudo cargar el mapa de " + destino.nombre(), ex);
            String detalle = ex instanceof OutOfMemoryError
                    ? "memoria insuficiente; ejecuta con -Dapp.xmx=2g o más"
                    : ex instanceof java.nio.file.NoSuchFileException nsf && nsf.getReason() != null
                    ? nsf.getReason() : String.valueOf(ex.getMessage());
            Platform.runLater(() -> {
                if (generacion != generacionCarga.get()) return;
                mensajeCarga = "No se pudo cargar " + destino.nombre() + ": " + detalle;
                redibujar();
                if (onError != null) onError.accept(mensajeCarga);
            });
        }
    }

    public PieroGCiudadMapa getCiudad() { return ciudad; }

    public boolean isCargado() { return cargado; }

    // ==================================================================
    //  CÁMARA
    // ==================================================================

    private void recalcularEscalaBase() {
        if (canvas.getWidth() <= 1 || canvas.getHeight() <= 1) return;
        double margen = 0.05;
        escalaBase = Math.min(canvas.getWidth() * (1 - 2 * margen),
                canvas.getHeight() * (1 - 2 * margen));
        anchoAnterior = canvas.getWidth();
        altoAnterior = canvas.getHeight();
    }

    private void recalcularConservandoCentro() {
        double ancho = canvas.getWidth();
        double alto = canvas.getHeight();
        if (ancho <= 1 || alto <= 1) return;
        if (anchoAnterior > 1 && altoAnterior > 1) {
            double escalaAnterior = escalaBase * zoom;
            double cx = (anchoAnterior / 2.0 - offsetX) / escalaAnterior;
            double cy = (altoAnterior / 2.0 - offsetY) / escalaAnterior;
            escalaBase = Math.min(ancho, alto) * 0.9;
            offsetX = ancho / 2.0 - cx * escalaBase * zoom;
            offsetY = alto / 2.0 - cy * escalaBase * zoom;
        } else {
            recalcularEscalaBase();
        }
        anchoAnterior = ancho;
        altoAnterior = alto;
        redibujarMapa();
    }

    private void resetVista() {
        if (!cargado) return;
        recalcularEscalaBase();
        nivelZoom = 2;
        zoom = ZOOMS[nivelZoom];
        offsetX = (canvas.getWidth() - escalaBase * zoom) / 2.0;
        offsetY = (canvas.getHeight() - escalaBase * zoom) / 2.0;
        redibujarMapa();
    }

    private double pantallaX(double nx) { return nx * escalaBase * zoom + offsetX; }
    private double pantallaY(double ny) { return ny * escalaBase * zoom + offsetY; }

    private double lonANormX(double lon) { return (lon - proyector.getMinLon()) / proyector.getAnchoGeo(); }
    private double latANormY(double lat) { return (proyector.getMaxLat() - lat) / proyector.getAltoGeo(); }

    private void redibujar() { necesitaRedibujar = true; }

    private void redibujarMapa() {
        viasNecesitanRedibujarse = true;
        redibujar();
    }

    /** Durante el arrastre se mueve la imagen de calles ya pintada (sin repintarla). */
    private void desplazarCapaVias() {
        double dx = offsetX - offsetXVias;
        double dy = offsetY - offsetYVias;
        capaVias.setTranslateX(dx);
        capaVias.setTranslateY(dy);
        // Si el hueco descubierto es grande, se repinta (limitado por el presupuesto de FPS).
        if (Math.abs(dx) > getWidth() * MARGEN_VIEWPORT * 0.7
                || Math.abs(dy) > getHeight() * MARGEN_VIEWPORT * 0.7) {
            viasNecesitanRedibujarse = true;
        }
        redibujar();
    }

    // ==================================================================
    //  RENDER
    // ==================================================================

    private void leerFotoSimulacion() {
        Supplier<EberthMMotorSimulacion.Instantanea> fuente = fuenteVehiculos;
        if (fuente == null) return;
        EberthMMotorSimulacion.Instantanea foto = fuente.get();
        if (foto == null || foto.version() == versionVehiculos) return;
        versionVehiculos = foto.version();
        vehiculos = foto.vehiculos();
        paradasEnEspera = foto.paradas();
        caminantes = foto.caminantes();
        zonasSinCobertura = foto.zonasSinCoberturaXY();
        segundosSimulados = foto.segundosSimulados();
        necesitaRedibujar = true;
    }

    private void renderizar() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (!cargado) {
            dibujarMensajeCarga(w, h);
            return;
        }
        if (viasNecesitanRedibujarse) renderizarVias();
        gc.clearRect(0, 0, w, h);

        // LOD: lejano (< 5) solo rutas (en la capa cacheada) y micros; medio (5-15) suma semáforos,
        // eventos y gente; cercano (> 15) todo. Cada dibujo se descarta solo según su zoom.
        // Las rutas viven en la capa de calles: no se recorren sus tramos en cada fotograma.
        if (zoom >= ZOOM_LEJANO) {
            dibujarViasCerradas(gc);
            dibujarHumoTrancaderas(gc);
            if (mostrarCalor) prepararAgrupacionVehiculos();
            if (mostrarCalor) dibujarMapaCalor(gc);
            dibujarPuntos(gc, false);
            dibujarEventos(gc);
            dibujarZonasSinCobertura(gc);
            dibujarGenteDemanda(gc);
            dibujarPasajerosEnParadas(gc);
            dibujarCaminantes(gc);
            dibujarSemaforos(gc);
        }
        dibujarVehiculos(gc);
        if (zoom >= ZOOM_LEJANO) dibujarLluvia(gc, w, h);
        dibujarLeyenda(gc);
    }

    private void dibujarMensajeCarga(double w, double h) {
        GraphicsContext fondoGc = capaVias.getGraphicsContext2D();
        capaVias.setTranslateX(0);
        capaVias.setTranslateY(0);
        fondoGc.setFill(fondo);
        fondoGc.fillRect(0, 0, capaVias.getWidth(), capaVias.getHeight());
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);
        gc.setFont(FONT_MENSAJE);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.setFill(colorPuntoTexto);
        gc.fillText(mensajeCarga, w / 2, h / 2, Math.max(50, w - 40));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.BASELINE);
        // Al terminar la carga, resetVista() vuelve a marcar la capa de vías.
        viasNecesitanRedibujarse = false;
    }

    private void renderizarVias() {
        viasNecesitanRedibujarse = false;
        offsetXVias = offsetX;
        offsetYVias = offsetY;
        capaVias.setTranslateX(0);
        capaVias.setTranslateY(0);
        GraphicsContext gc = capaVias.getGraphicsContext2D();
        gc.setFill(fondo);
        gc.fillRect(0, 0, capaVias.getWidth(), capaVias.getHeight());
        // Se dibuja en coordenadas de pantalla: la capa empieza MARGEN_VIEWPORT fuera de ella.
        double w = canvas.getWidth(), h = canvas.getHeight();
        gc.save();
        gc.translate(w * MARGEN_VIEWPORT, h * MARGEN_VIEWPORT);
        if (croquisActivo()) dibujarZonasUrbanas(gc, w, h);
        List<PieroGVia> visibles = viasVisibles(w, h);
        dibujarVias(gc, visibles);
        dibujarRutas(gc);
        dibujarNombresVias(gc, visibles);
        dibujarNombresZonas(gc);
        dibujarPuntos(gc, true);
        gc.restore();
        publicarInfoMapa(visibles.size());
    }

    /**
     * Viewport culling + LOD de calles: consulta el STRtree con la caja visible
     * y, en mapas masivos con zoom lejano, usa solo el índice de vías principales.
     * Las vías cuyo tamaño en pantalla es menor a un píxel se omiten.
     */
    @SuppressWarnings("unchecked")
    private List<PieroGVia> viasVisibles(double w, double h) {
        if (indiceVias == null) return List.of();
        double escala = escalaBase * zoom;
        double mx = w * MARGEN_VIEWPORT, my = h * MARGEN_VIEWPORT;
        Envelope caja = new Envelope((-mx - offsetX) / escala, (w + mx - offsetX) / escala,
                (-my - offsetY) / escala, (h + my - offsetY) / escala);
        boolean soloPrincipales = vias != null && vias.size() > UMBRAL_MAPA_GRANDE
                && zoom < ZOOM_DETALLE_MAPA_GRANDE;
        List<PieroGVia> candidatas = (soloPrincipales ? indiceViasPrincipales : indiceVias).query(caja);
        boolean filtrarSecundarias = candidatas.size() > MAX_VIAS_DETALLE;
        double minimo = 1.0 / escala;
        List<PieroGVia> visibles = new ArrayList<>(Math.min(candidatas.size(), MAX_VIAS_DETALLE));
        for (PieroGVia via : candidatas) {
            if (filtrarSecundarias && !esPrincipal(via.highway)) continue;
            if (via.maxX - via.minX < minimo && via.maxY - via.minY < minimo) continue;
            visibles.add(via);
        }
        return visibles;
    }

    /** Publica el resumen del mapa para la barra de estado (no se pinta sobre el canvas). */
    private void publicarInfoMapa(int visibles) {
        if (onInfoMapa == null) return;
        String texto = ciudad.nombre() + " · " + (vias == null ? 0 : vias.size()) + " vías ("
                + visibles + " visibles) · zoom " + String.format("%.1f", zoom)
                + " · doble clic: encuadrar";
        if (texto.equals(ultimaInfoMapa)) return;
        ultimaInfoMapa = texto;
        onInfoMapa.accept(texto);
    }

    private void dibujarVias(GraphicsContext gc, List<PieroGVia> visibles) {
        if (croquisActivo()) {
            dibujarViasCroquis(gc, visibles);
            return;
        }
        for (List<PieroGVia> g : gruposVias) g.clear();
        for (PieroGVia v : visibles) {
            if (v.highway == null || v.projX == null || v.projX.length < 2) continue;
            gruposVias.get(indiceColorVia(v.highway)).add(v);
        }
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        for (int i = gruposVias.size() - 1; i >= 0; i--) {
            List<PieroGVia> grupo = gruposVias.get(i);
            if (grupo.isEmpty()) continue;
            boolean secundaria = esSecundaria(grupo.get(0).highway);
            boolean avenida = zoom >= 6 && i <= 2;
            boolean carriles = !avenida && zoom >= ZOOM_DOBLE_VIA;
            gc.beginPath();
            for (PieroGVia v : grupo) {
                // Doble vía: dos líneas paralelas (una por sentido); sentido único: una sola.
                if (carriles && !v.oneway) {
                    trazarViaDesfasada(gc, v, DESFASE_CARRIL_PX);
                    trazarViaDesfasada(gc, v, -DESFASE_CARRIL_PX);
                } else {
                    trazarVia(gc, v);
                }
            }
            gc.setStroke(coloresVias[i]);
            // Calles de 1.5 px (1 px las secundarias). Las avenidas se pintan con un ancho
            // mayor y se ahueca el centro con el color del fondo: quedan dos líneas paralelas.
            gc.setLineWidth(avenida ? ANCHO_VIA_PX * 2 + ANCHO_HUECO_AVENIDA_PX
                    : zoom < ZOOM_LEJANO ? (secundaria ? 0.7 : 1.0)
                    : secundaria ? 1.0 : ANCHO_VIA_PX);
            gc.stroke();
            if (avenida) {
                gc.setStroke(fondo);
                gc.setLineWidth(ANCHO_HUECO_AVENIDA_PX);
                gc.stroke();
            }
        }
    }

    /** Zoom desde el que las vías de doble sentido se dibujan con un carril por sentido. */
    private static final double ZOOM_DOBLE_VIA = 8;
    /** Separación (px) de cada carril respecto del eje de la vía: 2 px entre ambos sentidos. */
    private static final double DESFASE_CARRIL_PX = 1;

    /** Traza la vía desplazada {@code desfase} px hacia la derecha de su sentido de digitalización. */
    private void trazarViaDesfasada(GraphicsContext gc, PieroGVia v, double desfase) {
        int n = v.projX.length;
        for (int j = 0; j < n; j++) {
            double x = pantallaX(v.projX[j]), y = pantallaY(v.projY[j]);
            int a = Math.max(0, j - 1), b = Math.min(n - 1, j + 1);
            double dx = pantallaX(v.projX[b]) - pantallaX(v.projX[a]);
            double dy = pantallaY(v.projY[b]) - pantallaY(v.projY[a]);
            double largo = Math.hypot(dx, dy);
            if (largo > 0) {
                x += -dy / largo * desfase;
                y += dx / largo * desfase;
            }
            if (j == 0) gc.moveTo(x, y); else gc.lineTo(x, y);
        }
    }

    private void trazarVia(GraphicsContext gc, PieroGVia v) {
        gc.moveTo(pantallaX(v.projX[0]), pantallaY(v.projY[0]));
        for (int j = 1; j < v.projX.length; j++) {
            gc.lineTo(pantallaX(v.projX[j]), pantallaY(v.projY[j]));
        }
    }

    // ------------------------------------------------------------------ rutas

    private void dibujarRutas(GraphicsContext gc) {
        if (microIdaVuelta != null) {
            // Modo Ida/Vuelta: solo la micro elegida, ida azul y vuelta roja,
            // cada una a la derecha de su sentido, para verse ambas en calles compartidas.
            if (mostrarVuelta && microIdaVuelta.tieneRetorno()) {
                dibujarTrayecto(gc, microIdaVuelta.trayectoVuelta(), COLOR_VUELTA, DESFASE_CARRIL, true, true);
            }
            if (mostrarIda) {
                dibujarTrayecto(gc, microIdaVuelta.trayecto(), COLOR_IDA, DESFASE_CARRIL, false, true);
            }
            return;
        }
        // Vista normal: todas las micros marcadas, cada una con su color.
        for (EberthMServicioMicro servicio : rutasMicros) {
            if (!rutasMicrosVisibles.contains(servicio.id())) continue;
            Color color = color(servicio.color());
            if (servicio.tieneRetorno()) {
                dibujarTrayecto(gc, servicio.trayectoVuelta(), color, DESFASE_CARRIL, true, false);
            }
            dibujarTrayecto(gc, servicio.trayecto(), color, DESFASE_CARRIL, false, false);
        }
        if (mostrarVuelta && trayectoRetorno != null) {
            dibujarTrayecto(gc, trayectoRetorno, COLOR_VUELTA, DESFASE_CARRIL, true, true);
        }
        if (mostrarIda && trayectoPartida != null) {
            dibujarTrayecto(gc, trayectoPartida, COLOR_IDA, DESFASE_CARRIL, false, true);
        }
    }

    /**
     * Dibuja un trayecto con borde de contraste en la capa cacheada de calles (que se extiende
     * {@link #MARGEN_VIEWPORT} fuera de la pantalla). Los tramos fuera de esa zona se descartan y
     * los vértices a menos de {@code minPx} del último se saltan: con zoom lejano 50 rutas caben en
     * unos pocos miles de segmentos. {@code desfase} separa lateralmente (px) ida y vuelta.
     */
    private void dibujarTrayecto(GraphicsContext gc, LuisYTrayecto t, Color color, double desfase,
                                 boolean discontinua, boolean flechas) {
        if (t == null || t.esVacia()) return;
        double w = canvas.getWidth(), h = canvas.getHeight();
        double m = 12;
        double xMin = -w * MARGEN_VIEWPORT - m, xMax = w * (1 + MARGEN_VIEWPORT) + m;
        double yMin = -h * MARGEN_VIEWPORT - m, yMax = h * (1 + MARGEN_VIEWPORT) + m;
        boolean lejano = zoom < ZOOM_LEJANO;
        // Zoom lejano: solo la línea (sin borde ni guiones); el sentido de vuelta se omite.
        if (lejano && discontinua) return;
        double minPx = lejano ? 2.0 : zoom < ZOOM_CERCANO ? 0.75 : 0;
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        gc.beginPath();
        int dibujados = 0;
        boolean enCamino = false;
        double ultimoX = 0, ultimoY = 0;
        for (PieroGEdge edge : t.edges()) {
            double ax = pantallaX(edge.origen.x), ay = pantallaY(edge.origen.y);
            double bx = pantallaX(edge.destino.x), by = pantallaY(edge.destino.y);
            if ((ax < xMin && bx < xMin) || (ax > xMax && bx > xMax)
                    || (ay < yMin && by < yMin) || (ay > yMax && by > yMax)) {
                enCamino = false;
                continue;
            }
            double dx = bx - ax, dy = by - ay;
            double largo = Math.hypot(dx, dy);
            double nx = largo == 0 ? 0 : -dy / largo * desfase;
            double ny = largo == 0 ? 0 : dx / largo * desfase;
            if (!enCamino) {
                ultimoX = ax + nx;
                ultimoY = ay + ny;
                gc.moveTo(ultimoX, ultimoY);
                enCamino = true;
            } else if (minPx == 0) {
                gc.lineTo(ax + nx, ay + ny);
            }
            double sx = bx + nx, sy = by + ny;
            if (minPx > 0 && Math.hypot(sx - ultimoX, sy - ultimoY) < minPx) continue;
            gc.lineTo(sx, sy);
            ultimoX = sx;
            ultimoY = sy;
            dibujados++;
        }
        if (dibujados == 0) return;
        if (!lejano) {
            gc.setStroke(fondoClaro() ? Color.web("#FFFFFF", 0.95) : Color.web("#10111A", 0.9));
            gc.setLineWidth(8);
            gc.stroke();
        }
        gc.setStroke(color);
        gc.setLineWidth(lejano ? 2.5 : 4.5);
        if (discontinua) gc.setLineDashes(10, 10);
        gc.stroke();
        gc.setLineDashes((double[]) null);
        if (flechas && zoom >= 3) dibujarFlechas(gc, t, color, desfase);
    }

    /** Chevrons de sentido cada ~150 px a lo largo del trayecto. */
    private void dibujarFlechas(GraphicsContext gc, LuisYTrayecto t, Color color, double desfase) {
        double w = canvas.getWidth(), h = canvas.getHeight();
        double acumulado = 75;
        gc.setStroke(fondoClaro() ? Color.WHITE : Color.web("#10111A"));
        gc.setLineWidth(2);
        gc.setFill(color);
        for (PieroGEdge edge : t.edges()) {
            double ax = pantallaX(edge.origen.x), ay = pantallaY(edge.origen.y);
            double bx = pantallaX(edge.destino.x), by = pantallaY(edge.destino.y);
            double largo = Math.hypot(bx - ax, by - ay);
            if (largo == 0) continue;
            double ux = (bx - ax) / largo, uy = (by - ay) / largo;
            double recorrido = 0;
            while (acumulado <= largo - recorrido) {
                recorrido += acumulado;
                acumulado = 150;
                double px = ax + ux * recorrido - uy * desfase;
                double py = ay + uy * recorrido + ux * desfase;
                if (px < -w * MARGEN_VIEWPORT || py < -h * MARGEN_VIEWPORT
                        || px > w * (1 + MARGEN_VIEWPORT) || py > h * (1 + MARGEN_VIEWPORT)) continue;
                double[] xs = {px + ux * 6, px - ux * 5 - uy * 5, px - ux * 5 + uy * 5};
                double[] ys = {py + uy * 6, py - uy * 5 + ux * 5, py - uy * 5 - ux * 5};
                gc.strokePolygon(xs, ys, 3);
                gc.fillPolygon(xs, ys, 3);
            }
            acumulado -= largo - recorrido;
        }
    }

    private void dibujarViasCerradas(GraphicsContext gc) {
        dibujarTramos(gc, viasTrancadera, Color.ORANGE, Math.max(4, Math.sqrt(zoom) * 1.5));
        dibujarTramos(gc, viasCerradas, Color.RED, Math.max(6, Math.sqrt(zoom) * 2.5));
    }

    private void dibujarTramos(GraphicsContext gc, List<PieroGEdge> tramos, Color color, double grosor) {
        if (tramos.isEmpty()) return;
        double w = canvas.getWidth(), h = canvas.getHeight();
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setStroke(color);
        gc.setLineWidth(grosor);
        for (PieroGEdge edge : tramos) {
            double ax = pantallaX(edge.origen.x), ay = pantallaY(edge.origen.y);
            double bx = pantallaX(edge.destino.x), by = pantallaY(edge.destino.y);
            if (Math.max(ax, bx) < 0 || Math.min(ax, bx) > w
                    || Math.max(ay, by) < 0 || Math.min(ay, by) > h) continue;
            gc.strokeLine(ax, ay, bx, by);
        }
    }

    private void dibujarNombresVias(GraphicsContext gc, List<PieroGVia> visibles) {
        if (zoom <= ZOOM_CERCANO) return;
        double w = canvas.getWidth(), h = canvas.getHeight();
        double mx = w * MARGEN_VIEWPORT, my = h * MARGEN_VIEWPORT;
        Set<Long> celdasOcupadas = new HashSet<>();
        double celda = Math.max(85, 140 - zoom * 2);
        gc.setFont(Font.font("System", Math.min(13, 10 + Math.log(zoom) * 0.6)));
        for (PieroGVia via : visibles) {
            if (via.name == null || via.name.isBlank()
                    || via.projX == null || via.projX.length < 2) continue;
            int medio = via.projX.length / 2;
            double x = pantallaX(via.projX[medio]);
            double y = pantallaY(via.projY[medio]);
            if (x < 8 - mx || x > w + mx - 8 || y < 12 - my || y > h + my - 4) {
                continue;
            }
            long celdaId = ((long) Math.floor(x / celda) << 32)
                    | ((long) Math.floor(y / celda) & 0xffffffffL);
            if (!celdasOcupadas.add(celdaId)) continue;
            String texto = via.name.length() > 40
                    ? via.name.substring(0, 37) + "..." : via.name;
            gc.setLineWidth(3);
            gc.setStroke(fondoClaro() ? Color.WHITE : Color.BLACK);
            gc.strokeText(texto, x + 1, y + 1);
            gc.setFill(fondoClaro() ? Color.web("#202735") : Color.WHITE);
            gc.fillText(texto, x, y);
        }
    }

    // ------------------------------------------------------------------ puntos (LOD)

    /**
     * Puntos de interés con nivel de detalle:
     * <ul>
     *   <li>Solo los visibles (culling por caja de pantalla).</li>
     *   <li>Puntos que caen en la misma celda de {@value #CELDA_CLUSTER} px se
     *       agrupan en un clúster con contador (salvo inicios/finales de ruta).</li>
     *   <li>Los nombres solo se dibujan con zoom cercano y sin solaparse.</li>
     * </ul>
     */
    /**
     * @param estatica {@code true}: pinta los POIs corrientes y clústeres en la capa de calles
     *                 (una vez por zoom/arrastre); {@code false}: solo los extremos de ruta,
     *                 que cambian con las rutas y van en la capa dinámica.
     */
    private void dibujarPuntos(GraphicsContext gc, boolean estatica) {
        if (puntos.isEmpty()) return;
        // Zoom lejano: solo se conservan los extremos de ruta; los POIs corrientes se omiten.
        // Con zoom < ZOOM_MAPA_LIMPIO no se dibuja ningún clúster ni POI suelto: solo rutas y micros.
        if (zoom < ZOOM_LEJANO) return; // zoom lejano: solo rutas y micros
        boolean soloExtremos = zoom < ZOOM_MAPA_LIMPIO;
        double w = canvas.getWidth(), h = canvas.getHeight();
        boolean agrupar = etiquetasPuntos != EtiquetasPuntos.SIEMPRE && zoom < ZOOM_SIN_CLUSTER;
        // Sin nombres con zoom lejano, sea cual sea el modo elegido.
        boolean rotular = zoom >= ZOOM_ETIQUETAS_PUNTOS && etiquetasPuntos != EtiquetasPuntos.OCULTAS;

        Map<Long, List<double[]>> celdas = new HashMap<>();
        List<LuisYPuntoImportante> sueltos = new ArrayList<>();
        List<double[]> posicionesSueltos = new ArrayList<>();
        List<LuisYPuntoImportante> extremos = new ArrayList<>();
        List<double[]> posicionesExtremos = new ArrayList<>();
        for (LuisYPuntoImportante p : puntos) {
            double px = pantallaX(lonANormX(p.lon()));
            double py = pantallaY(latANormY(p.lat()));
            if (!estaEnPantalla(px, py, 12, 12)) continue;
            if (puntosInicioRutas.contains(p.id()) || puntosFinalRutas.contains(p.id())) {
                if (estatica) continue;
                extremos.add(p);
                posicionesExtremos.add(new double[]{px, py});
                continue;
            }
            if (soloExtremos || !estatica) continue;
            if (agrupar) {
                long clave = ((long) Math.floor(px / CELDA_CLUSTER) << 32)
                        | ((long) Math.floor(py / CELDA_CLUSTER) & 0xffffffffL);
                celdas.computeIfAbsent(clave, k -> new ArrayList<>())
                        .add(new double[]{px, py, sueltos.size()});
            }
            sueltos.add(p);
            posicionesSueltos.add(new double[]{px, py});
        }

        OcupacionEtiquetas ocupadas = new OcupacionEtiquetas();
        boolean[] enCluster = new boolean[sueltos.size()];
        if (agrupar) {
            for (List<double[]> grupo : celdas.values()) {
                if (grupo.size() < 2) continue;
                double sx = 0, sy = 0;
                for (double[] g : grupo) {
                    sx += g[0];
                    sy += g[1];
                    enCluster[(int) g[2]] = true;
                }
                dibujarCluster(gc, sx / grupo.size(), sy / grupo.size(), grupo.size(), ocupadas);
            }
        }
        for (int i = 0; i < sueltos.size(); i++) {
            if (enCluster[i]) continue;
            LuisYPuntoImportante p = sueltos.get(i);
            double[] pos = posicionesSueltos.get(i);
            Color color = color(LuisYPaletaColores.colorCategoria(p.tipo()));
            dibujarMarcador(gc, pos[0], pos[1], 6, color);
            ocupadas.ocupar(pos[0] - 7, pos[1] - 7, 14, 14);
        }
        // Inicios/finales siempre visibles y rotulados (encima de todo; vacío en la capa estática).
        for (int i = 0; i < extremos.size(); i++) {
            LuisYPuntoImportante p = extremos.get(i);
            double[] pos = posicionesExtremos.get(i);
            boolean inicio = puntosInicioRutas.contains(p.id());
            boolean fin = puntosFinalRutas.contains(p.id());
            Color color = inicio && fin ? COLOR_EXTREMO_COMPARTIDO
                    : inicio ? COLOR_INICIO_RUTA : COLOR_FINAL_RUTA;
            dibujarMarcador(gc, pos[0], pos[1], 8, color);
            if (zoom < ZOOM_ETIQUETAS_PUNTOS) continue;
            String etiqueta = (inicio && fin ? "INICIO/FINAL · " : inicio ? "INICIO · " : "FINAL · ")
                    + p.nombre();
            dibujarEtiqueta(gc, etiqueta, pos[0] + 11, pos[1] - 7, FONT_PUNTO_EXTREMO, ocupadas, true);
        }
        if (!rotular || !estatica) return;
        for (int i = 0; i < sueltos.size(); i++) {
            if (enCluster[i]) continue;
            double[] pos = posicionesSueltos.get(i);
            dibujarEtiqueta(gc, sueltos.get(i).nombre(), pos[0] + 9, pos[1] - 5, FONT_PUNTO,
                    ocupadas, false);
        }
    }

    private void dibujarMarcador(GraphicsContext gc, double px, double py, double radio, Color color) {
        gc.setFill(color);
        gc.fillOval(px - radio, py - radio, radio * 2, radio * 2);
        gc.setStroke(colorPuntoBorde);
        gc.setLineWidth(2);
        gc.strokeOval(px - radio, py - radio, radio * 2, radio * 2);
    }

    private void dibujarCluster(GraphicsContext gc, double x, double y, int cantidad,
                                OcupacionEtiquetas ocupadas) {
        double radio = Math.min(22, 11 + Math.log(cantidad) * 3.2);
        gc.setFill(colorCluster.deriveColor(0, 1, 1, 0.28));
        gc.fillOval(x - radio - 4, y - radio - 4, (radio + 4) * 2, (radio + 4) * 2);
        gc.setFill(colorCluster);
        gc.fillOval(x - radio, y - radio, radio * 2, radio * 2);
        gc.setStroke(colorPuntoBorde);
        gc.setLineWidth(2);
        gc.strokeOval(x - radio, y - radio, radio * 2, radio * 2);
        gc.setFill(fondoClaro() ? Color.WHITE : Color.web("#11111B"));
        gc.setFont(FONT_CLUSTER);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText(Integer.toString(cantidad), x, y);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.BASELINE);
        ocupadas.ocupar(x - radio - 4, y - radio - 4, (radio + 4) * 2, (radio + 4) * 2);
    }

    /** Dibuja un texto con halo si no choca con otra etiqueta (o siempre, si {@code forzar}). */
    private void dibujarEtiqueta(GraphicsContext gc, String texto, double x, double y, Font fuente,
                                 OcupacionEtiquetas ocupadas, boolean forzar) {
        String corto = texto.length() > 42 ? texto.substring(0, 40) + "…" : texto;
        double ancho = anchoTexto(corto, fuente);
        double alto = 15;
        if (!forzar && ocupadas.choca(x - 2, y - alto + 3, ancho + 4, alto + 2)) return;
        ocupadas.ocupar(x - 2, y - alto + 3, ancho + 4, alto + 2);
        gc.setFont(fuente);
        gc.setLineWidth(3.5);
        gc.setStroke(colorHalo);
        gc.strokeText(corto, x, y);
        gc.setFill(colorPuntoTexto);
        gc.fillText(corto, x, y);
    }

    private double anchoTexto(String texto, Font fuente) {
        String clave = (fuente == FONT_PUNTO_EXTREMO ? "B" : "N") + texto;
        Double ancho = anchosTexto.get(clave);
        if (ancho != null) return ancho;
        MEDIDOR.setFont(fuente);
        MEDIDOR.setText(texto);
        double medido = MEDIDOR.getLayoutBounds().getWidth();
        if (anchosTexto.size() > 20_000) anchosTexto.clear();
        anchosTexto.put(clave, medido);
        return medido;
    }

    /** Rejilla hash de rectángulos ocupados por etiquetas (detección de choques O(1)). */
    private static final class OcupacionEtiquetas {
        private static final double CELDA = 64;
        private final Map<Long, List<double[]>> celdas = new HashMap<>();

        void ocupar(double x, double y, double w, double h) {
            double[] r = {x, y, x + w, y + h};
            recorrer(r, (lista) -> lista.add(r), true);
        }

        boolean choca(double x, double y, double w, double h) {
            double[] r = {x, y, x + w, y + h};
            boolean[] choque = {false};
            recorrer(r, lista -> {
                for (double[] o : lista) {
                    if (r[0] < o[2] && r[2] > o[0] && r[1] < o[3] && r[3] > o[1]) {
                        choque[0] = true;
                        return;
                    }
                }
            }, false);
            return choque[0];
        }

        private void recorrer(double[] r, java.util.function.Consumer<List<double[]>> accion,
                              boolean crear) {
            int c0 = (int) Math.floor(r[0] / CELDA), c1 = (int) Math.floor(r[2] / CELDA);
            int f0 = (int) Math.floor(r[1] / CELDA), f1 = (int) Math.floor(r[3] / CELDA);
            for (int c = c0; c <= c1; c++) {
                for (int f = f0; f <= f1; f++) {
                    long clave = ((long) c << 32) | (f & 0xffffffffL);
                    List<double[]> lista = crear ? celdas.computeIfAbsent(clave, k -> new ArrayList<>())
                            : celdas.get(clave);
                    if (lista != null) accion.accept(lista);
                }
            }
        }
    }

    private void actualizarExtremosRutas() {
        Set<String> inicios = new HashSet<>();
        Set<String> finales = new HashSet<>();
        if (microIdaVuelta != null) {
            agregarExtremos(microIdaVuelta.trayecto(), inicios, finales);
        } else {
            boolean hayRutasActivas = false;
            for (EberthMServicioMicro ruta : rutasMicros) {
                if (!rutasMicrosVisibles.contains(ruta.id())) continue;
                agregarExtremos(ruta.trayecto(), inicios, finales);
                hayRutasActivas = true;
            }
            if (!hayRutasActivas) {
                agregarExtremos(trayectoPartida, inicios, finales);
                agregarExtremos(trayectoRetorno, inicios, finales);
            }
        }
        puntosInicioRutas = Set.copyOf(inicios);
        puntosFinalRutas = Set.copyOf(finales);
    }

    private static void agregarExtremos(LuisYTrayecto trayecto, Set<String> inicios,
                                        Set<String> finales) {
        if (trayecto == null || trayecto.puntos().isEmpty()) return;
        inicios.add(trayecto.puntos().getFirst().id());
        finales.add(trayecto.puntos().getLast().id());
    }

    // ------------------------------------------------------------------ eventos y vehículos

    private void dibujarMapaCalor(GraphicsContext gc) {
        for (EberthMEventoTrafico evento : eventos) {
            if (evento.tipo().esZonal()) continue;
            double radio = Math.max(20, 42 * Math.min(1, zoom / 4));
            Color color = evento.tipo() == EberthMTipoEvento.TRANCADERA
                    ? Color.ORANGERED : Color.GOLD;
            gc.setFill(color.deriveColor(0, 1, 1, 0.17));
            gc.fillOval(pantallaX(lonANormX(evento.lon())) - radio,
                    pantallaY(latANormY(evento.lat())) - radio, radio * 2, radio * 2);
        }
        for (int i = 0; i < cantidadVehiculosPorCelda.length; i++) {
            int cantidad = cantidadVehiculosPorCelda[i];
            if (cantidad == 0) continue;
            double px = (i % columnasVehiculos + 0.5) * TAMANO_CELDA_VEHICULOS;
            double py = (i / columnasVehiculos + 0.5) * TAMANO_CELDA_VEHICULOS;
            double radio = Math.min(40, 8 + Math.sqrt(cantidad) * 2);
            gc.setFill(Color.ORANGERED.deriveColor(0, 1, 1, 0.16));
            gc.fillOval(px - radio, py - radio, radio * 2, radio * 2);
        }
    }

    private static final int GOTAS_POR_LLUVIA = 30;
    private static final double RADIO_LLUVIA_GRADIENTE_PX = 120;
    private static final double LARGO_GOTA = 9;
    private static final double VELOCIDAD_GOTA_PX_S = 260;

    /**
     * Lluvia zonal: círculo azul translúcido de 1 km y gotas diagonales que caen dentro,
     * recortadas al círculo. Las gotas son función del reloj (sin estado) y se reparten
     * con un hash por índice, así el patrón es estable entre fotogramas.
     */
    private void dibujarLluvia(GraphicsContext gc, double w, double h) {
        if (!hayLluvia) return;
        double t = System.nanoTime() / 1e9;
        for (EberthMEventoTrafico evento : eventos) {
            if (evento.tipo() != EberthMTipoEvento.LLUVIA) continue;
            double cx = pantallaX(lonANormX(evento.lon()));
            double cy = pantallaY(latANormY(evento.lat()));
            double dLon = evento.radioEfectivoMetros()
                    / (111_320 * Math.cos(Math.toRadians(evento.lat())));
            double r = Math.abs(pantallaX(lonANormX(evento.lon() + dLon)) - cx);
            if (r < 2 || cx + r < 0 || cy + r < 0 || cx - r > w || cy - r > h) continue;

            if (r > RADIO_LLUVIA_GRADIENTE_PX) {
                // Zona grande: degradado (casi transparente al centro) para no saturar el mapa.
                gc.setFill(new RadialGradient(0, 0, cx, cy, r, false, CycleMethod.NO_CYCLE,
                        new Stop(0, Color.DODGERBLUE.deriveColor(0, 1, 1, 0.03)),
                        new Stop(0.7, Color.DODGERBLUE.deriveColor(0, 1, 1, 0.10)),
                        new Stop(1, Color.DODGERBLUE.deriveColor(0, 1, 1, 0.28))));
            } else {
                gc.setFill(Color.DODGERBLUE.deriveColor(0, 1, 1, 0.18));
            }
            gc.fillOval(cx - r, cy - r, r * 2, r * 2);
            gc.setStroke(Color.DODGERBLUE.deriveColor(0, 1, 1, 0.8));
            gc.setLineWidth(1.5);
            gc.strokeOval(cx - r, cy - r, r * 2, r * 2);

            gc.save();
            gc.beginPath();
            gc.arc(cx, cy, r, r, 0, 360);
            gc.closePath();
            gc.clip();
            gc.setStroke(Color.web("#CFE8FF", 0.75));
            gc.setLineWidth(1.2);
            double lado = r * 2;
            double caida = (t * VELOCIDAD_GOTA_PX_S) % lado;
            for (int i = 0; i < GOTAS_POR_LLUVIA; i++) {
                double fx = hash01(i * 2L + 1), fy = hash01(i * 2L + 2);
                double x = cx - r + fx * lado;
                double y = cy - r + (fy * lado + caida) % lado;
                gc.strokeLine(x, y, x - LARGO_GOTA * 0.4, y + LARGO_GOTA);
            }
            gc.restore();
        }
    }

    /** Hash determinista a [0, 1). */
    private static double hash01(long n) {
        long x = n * 0x9E3779B97F4A7C15L;
        x ^= x >>> 29;
        x *= 0xBF58476D1CE4E5B9L;
        x ^= x >>> 32;
        return (x & 0xFFFFFFL) / (double) 0x1000000;
    }

    private void dibujarEventos(GraphicsContext gc) {
        double w = canvas.getWidth(), h = canvas.getHeight();
        OcupacionEtiquetas ocupadas = new OcupacionEtiquetas();
        for (EberthMEventoTrafico evento : eventos) {
            double px = pantallaX(lonANormX(evento.lon()));
            double py = pantallaY(latANormY(evento.lat()));
            if (px < -20 || py < -20 || px > w + 20 || py > h + 20) continue;
            Color colorEvento = switch (evento.tipo()) {
                case VIA_CERRADA, ACCIDENTE, MANIFESTACION -> Color.RED;
                case TRANCADERA, SEMAFORO_ROTO -> Color.ORANGE;
                case LLUVIA -> Color.DEEPSKYBLUE;
                case GASOLINERA -> Color.GOLD;
                case VENDEDORES_AMBULANTES -> Color.MEDIUMSEAGREEN;
                case POLICIA_TRANSITO -> Color.DODGERBLUE;
            };
            if (evento.tipo().bloqueaNodo()) {
                double radio = Math.max(24, 60 * Math.min(1, zoom / 4));
                gc.setFill(Color.RED.deriveColor(0, 1, 1, 0.3));
                gc.fillOval(px - radio, py - radio, radio * 2, radio * 2);
                gc.setStroke(Color.RED);
                gc.setLineWidth(2);
                gc.strokeOval(px - radio, py - radio, radio * 2, radio * 2);
            }
            if (evento.tipo() == EberthMTipoEvento.ACCIDENTE) {
                dibujarTrianguloAdvertencia(gc, px, py);
            } else if (evento.tipo().esPolicia()) {
                if (zoom > ZOOM_CERCANO) dibujarPolicia(gc, px, py, evento.rumbo());
            } else {
                gc.setFill(colorEvento);
                gc.fillOval(px - 8, py - 8, 16, 16);
                gc.setStroke(Color.WHITE);
                gc.setLineWidth(2);
                gc.strokeOval(px - 8, py - 8, 16, 16);
            }
            if (zoom >= 3) {
                dibujarEtiqueta(gc, evento.nombre(), px + 11, py + 5, FONT_PUNTO, ocupadas, false);
            }
        }
    }

    /**
     * Policía de tránsito: círculo azul con una «P» blanca que parpadea, flechas tenues hacia
     * los cuatro lados de la intersección y una flecha dorada, más grande, hacia donde guía.
     */
    private void dibujarPolicia(GraphicsContext gc, double px, double py, double rumbo) {
        double radio = 8;
        double fase = (System.nanoTime() / 1e9) % 1.0;
        gc.setGlobalAlpha(0.35 + 0.65 * (0.5 + 0.5 * Math.cos(fase * 2 * Math.PI * 2)));
        for (int k = 0; k < 4; k++) {
            dibujarFlecha(gc, px, py, k * Math.PI / 2, radio + 9, 5, Color.LIGHTSKYBLUE);
        }
        if (Double.isFinite(rumbo)) {
            dibujarFlecha(gc, px, py, rumbo, radio + 11, 8, Color.GOLD);
        }
        gc.setFill(Color.DODGERBLUE);
        gc.fillOval(px - radio, py - radio, radio * 2, radio * 2);
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(1.4);
        gc.strokeOval(px - radio, py - radio, radio * 2, radio * 2);
        gc.setFill(Color.WHITE);
        gc.setFont(FONT_CLUSTER);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText("P", px, py);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.BASELINE);
        gc.setGlobalAlpha(1.0);
    }

    /** Punta de flecha de {@code tam} px a {@code distancia} del centro, apuntando hacia {@code angulo}. */
    private void dibujarFlecha(GraphicsContext gc, double cx, double cy, double angulo,
                               double distancia, double tam, Color color) {
        double ux = Math.cos(angulo), uy = Math.sin(angulo);
        double bx = cx + ux * distancia, by = cy + uy * distancia;
        double[] xs = {bx + ux * tam, bx - uy * tam * 0.7, bx + uy * tam * 0.7};
        double[] ys = {by + uy * tam, by + ux * tam * 0.7, by - ux * tam * 0.7};
        gc.setFill(color);
        gc.fillPolygon(xs, ys, 3);
        gc.setStroke(Color.web("#10243F"));
        gc.setLineWidth(1);
        gc.strokePolygon(xs, ys, 3);
    }

    /** Triángulo de advertencia amarillo con borde rojo cuya opacidad pulsa una vez por segundo. */
    private void dibujarTrianguloAdvertencia(GraphicsContext gc, double px, double py) {
        double fase = (System.nanoTime() / 1e9) % 1.0;
        double opacidad = 0.4 + 0.6 * (0.5 + 0.5 * Math.cos(fase * 2 * Math.PI));
        double[] xs = {px, px - 12, px + 12};
        double[] ys = {py - 13, py + 10, py + 10};
        gc.setGlobalAlpha(opacidad);
        gc.setFill(Color.GOLD);
        gc.fillPolygon(xs, ys, 3);
        gc.setStroke(Color.RED);
        gc.setLineWidth(3);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        gc.strokePolygon(xs, ys, 3);
        gc.setStroke(Color.web("#7a0000"));
        gc.setLineWidth(2.2);
        gc.strokeLine(px, py - 5, px, py + 2);
        gc.fillOval(px - 1.4, py + 4, 2.8, 2.8);
        gc.setGlobalAlpha(1.0);
    }

    private static final int MAX_TRAMOS_CON_HUMO = 30;
    private static final int BOCANADAS_POR_TRAMO = 4;

    /**
     * Humo gris que sube y se desvanece sobre los tramos con trancadera. Solo se dibujan los
     * tramos visibles en pantalla (hasta {@link #MAX_TRAMOS_CON_HUMO}); la posición es función
     * del reloj y de un hash por tramo, sin estado.
     */
    private void dibujarHumoTrancaderas(GraphicsContext gc) {
        if (viasTrancadera.isEmpty()) return;
        double w = canvas.getWidth(), h = canvas.getHeight();
        double t = System.nanoTime() / 1e9;
        double alto = Math.clamp(10 + zoom * 2, 12, 30);
        int dibujados = 0;
        for (int i = 0; i < viasTrancadera.size() && dibujados < MAX_TRAMOS_CON_HUMO; i++) {
            PieroGEdge edge = viasTrancadera.get(i);
            double ax = pantallaX(edge.origen.x), ay = pantallaY(edge.origen.y);
            double bx = pantallaX(edge.destino.x), by = pantallaY(edge.destino.y);
            if (Math.max(ax, bx) < -20 || Math.min(ax, bx) > w + 20
                    || Math.max(ay, by) < -30 || Math.min(ay, by) > h + 30) continue;
            dibujados++;
            for (int k = 0; k < BOCANADAS_POR_TRAMO; k++) {
                long semilla = i * 8L + k;
                double fase = (t * 0.4 + hash01(semilla)) % 1.0;
                double pos = hash01(semilla + 1_000);
                double x = ax + (bx - ax) * pos + (hash01(semilla + 2_000) - 0.5) * 6;
                double y = ay + (by - ay) * pos - fase * alto;
                double radio = 2 + fase * 4;
                gc.setFill(Color.GRAY.deriveColor(0, 1, 1 + 0.4 * fase, 0.5 * (1 - fase)));
                gc.fillOval(x - radio, y - radio, radio * 2, radio * 2);
            }
        }
    }

    private static final int MAX_SEMAFOROS_DIBUJADOS = 2_000;

    /** Aro rojo/amarillo/verde pequeño en cada semáforo visible (solo con zoom cercano). */
    private void dibujarSemaforos(GraphicsContext gc) {
        PieroGGrafoVial g = grafo;
        if (zoom < ZOOM_LEJANO || g == null) return;
        List<PieroGNodo> nodos = g.getNodosConSemaforo(); // ya ordenados por x
        double w = canvas.getWidth(), h = canvas.getHeight();
        double escala = escalaBase * zoom;
        double xMin = (-w * MARGEN_CULLING - offsetX) / escala;
        double xMax = (w * (1 + MARGEN_CULLING) - offsetX) / escala;
        int desde = 0, hasta = nodos.size();
        while (desde < hasta) {
            int medio = (desde + hasta) >>> 1;
            if (nodos.get(medio).x < xMin) desde = medio + 1; else hasta = medio;
        }
        gc.setLineWidth(1.6);
        int dibujados = 0;
        for (int i = desde; i < nodos.size() && dibujados < MAX_SEMAFOROS_DIBUJADOS; i++) {
            PieroGNodo nodo = nodos.get(i);
            if (nodo.x > xMax) break;
            EberthMSemaforo semaforo = nodo.semaforo;
            double py = pantallaY(nodo.y);
            if (semaforo == null || !estaEnPantalla(pantallaX(nodo.x), py, 5, 5)) continue;
            // Cada mitad del aro muestra una fase: izquierda/derecha = accesos horizontales,
            // arriba/abajo = verticales.
            double sx = pantallaX(nodo.x) - 3;
            gc.setStroke(colorLuz(nodo, semaforo, EberthMSemaforo.Eje.HORIZONTAL));
            gc.strokeArc(sx, py - 3, 6, 6, -45, 90, ArcType.OPEN);
            gc.strokeArc(sx, py - 3, 6, 6, 135, 90, ArcType.OPEN);
            gc.setStroke(colorLuz(nodo, semaforo, EberthMSemaforo.Eje.VERTICAL));
            gc.strokeArc(sx, py - 3, 6, 6, 45, 90, ArcType.OPEN);
            gc.strokeArc(sx, py - 3, 6, 6, 225, 90, ArcType.OPEN);
            dibujados++;
        }
    }

    private Color colorLuz(PieroGNodo nodo, EberthMSemaforo semaforo, EberthMSemaforo.Eje eje) {
        if (nodo.semaforoRoto) return Color.RED;
        return switch (semaforo.estadoEn(segundosSimulados, eje, nodo.policiaTransito)) {
            case VERDE -> Color.LIMEGREEN;
            case AMARILLO -> Color.GOLD;
            case ROJO -> Color.RED;
        };
    }

    /** Gente de la demanda de la ciudad esperando en sus zonas de origen: un punto azul por grupo. */
    private void dibujarGenteDemanda(GraphicsContext gc) {
        if (genteDemanda.length == 0) return;
        double w = canvas.getWidth(), h = canvas.getHeight();
        double lado = Math.min(8, 3 + zoom * 0.3);
        gc.setFill(COLOR_GENTE_DEMANDA);
        // Un solo color y rectángulos: hasta 4 000 puntos sin una llamada de óvalo por cada uno.
        for (int i = 0; i + 1 < genteDemanda.length; i += 2) {
            double px = pantallaX(lonANormX(genteDemanda[i])), py = pantallaY(latANormY(genteDemanda[i + 1]));
            if (px < -4 || py < -4 || px > w + 4 || py > h + 4) continue;
            gc.fillRect(px - lado / 2, py - lado / 2, lado, lado);
        }
    }

    /** Indicador de demanda agregada: círculo con la cantidad de gente esperando en cada parada. */
    private void dibujarPasajerosEnParadas(GraphicsContext gc) {
        if (zoom < 6.0 || paradasEnEspera.isEmpty()) return;
        double w = canvas.getWidth(), h = canvas.getHeight();
        gc.setFont(FONT_VEHICULO);
        for (EberthMParadaEspera parada : paradasEnEspera) {
            double px = pantallaX(parada.x()), py = pantallaY(parada.y());
            if (px < -20 || py < -20 || px > w + 20 || py > h + 20) continue;
            double radio = Math.min(12, 6 + Math.sqrt(parada.esperando()) * 0.5);
            gc.setFill(Color.STEELBLUE.deriveColor(0, 1, 1, 0.85));
            gc.fillOval(px - radio, py - radio - 10, radio * 2, radio * 2);
            gc.setFill(Color.WHITE);
            gc.fillText(LuisYFormatoCantidad.abreviar(parada.esperando()), px - radio * 0.6, py - 10 + 3);
            if (parada.transbordo() > 0) {
                // Segundo viaje: círculo morado con los pasajeros que esperan su transbordo.
                gc.setFill(Color.MEDIUMPURPLE.deriveColor(0, 1, 1, 0.95));
                gc.fillOval(px + radio * 0.4, py - radio - 14, 12, 12);
                gc.setFill(Color.WHITE);
                gc.fillText(LuisYFormatoCantidad.abreviar(parada.transbordo()), px + radio * 0.4 + 2, py - radio - 4);
            }
        }
    }

    /** Pasajeros que caminan a la parada más cercana: puntos pequeños que se mueven despacio. */
    private void dibujarCaminantes(GraphicsContext gc) {
        if (zoom < 6.0 || caminantes.length == 0) return;
        double w = canvas.getWidth(), h = canvas.getHeight();
        for (int i = 0; i + 2 < caminantes.length; i += 3) {
            double px = pantallaX(caminantes[i]), py = pantallaY(caminantes[i + 1]);
            if (px < -4 || py < -4 || px > w + 4 || py > h + 4) continue;
            boolean transbordo = caminantes[i + 2] > 0;
            gc.setFill(transbordo ? Color.MEDIUMPURPLE : Color.ORANGE);
            double r = transbordo ? 2.5 : 1.5;
            gc.fillOval(px - r, py - r, r * 2, r * 2);
        }
    }

    /** Zonas donde hay demanda pero ninguna parada de ruta a ≤ 300 m: círculo gris punteado. */
    private void dibujarZonasSinCobertura(GraphicsContext gc) {
        // Con zoom lejano el mapa se satura: solo cuenta el contador del panel de estadísticas.
        if (zonasSinCobertura.length == 0 || zoom <= ZOOM_ZONAS_SIN_COBERTURA) return;
        double w = canvas.getWidth(), h = canvas.getHeight();
        double radio = 300 / 111_320.0 / proyector.getAltoGeo() * escalaBase * zoom;
        if (radio < 3) return;
        gc.setStroke(Color.GRAY.deriveColor(0, 1, 1, 0.8));
        gc.setFill(Color.GRAY.deriveColor(0, 1, 1, 0.08));
        gc.setLineWidth(1.5);
        gc.setLineDashes(6, 5);
        for (int i = 0; i + 2 < zonasSinCobertura.length; i += 3) {
            double px = pantallaX(zonasSinCobertura[i]), py = pantallaY(zonasSinCobertura[i + 1]);
            if (px + radio < 0 || py + radio < 0 || px - radio > w || py - radio > h) continue;
            gc.fillOval(px - radio, py - radio, radio * 2, radio * 2);
            gc.strokeOval(px - radio, py - radio, radio * 2, radio * 2);
        }
        gc.setLineDashes((double[]) null);
    }

    /** Culling: la caja (x, y centrada, ancho x alto) toca la pantalla más un margen del 10 %. */
    private boolean estaEnPantalla(double x, double y, double ancho, double alto) {
        double w = canvas.getWidth(), h = canvas.getHeight();
        double mx = w * MARGEN_CULLING, my = h * MARGEN_CULLING;
        return x + ancho / 2 >= -mx && x - ancho / 2 <= w + mx
                && y + alto / 2 >= -my && y - alto / 2 <= h + my;
    }

    /** Posiciones de los micros de un mismo color, para pintarlos con un solo {@code setFill}. */
    private static final class LoteVehiculos {
        double[] xy = new double[64];
        int n;

        void agregar(double x, double y) {
            if (n + 2 > xy.length) xy = java.util.Arrays.copyOf(xy, xy.length * 2);
            xy[n++] = x;
            xy[n++] = y;
        }
    }

    private final Map<Color, LoteVehiculos> lotesVehiculos = new java.util.IdentityHashMap<>();

    private void dibujarVehiculos(GraphicsContext gc) {
        if (zoom <= ZOOM_CERCANO) {
            // Zoom lejano: 1 px sin borde; zoom medio: 4x4. Sin formas ni etiquetas. Se agrupan por
            // color y se pintan por lotes: cientos de micros sin cambiar de pincel en cada uno.
            double lado = zoom < ZOOM_LEJANO ? 1 : 4;
            lotesVehiculos.values().forEach(l -> l.n = 0);
            int visibles = 0;
            for (EberthMPosicionVehiculo vehiculo : vehiculos) {
                double px = pantallaX(vehiculo.x()), py = pantallaY(vehiculo.y());
                if (!estaEnPantalla(px, py, lado, lado)) continue;
                visibles++;
                lotesVehiculos.computeIfAbsent(colorVehiculo(vehiculo), c -> new LoteVehiculos())
                        .agregar(px - lado / 2, py - lado / 2);
            }
            for (var lote : lotesVehiculos.entrySet()) {
                LoteVehiculos l = lote.getValue();
                if (l.n == 0) continue;
                gc.setFill(lote.getKey());
                for (int i = 0; i < l.n; i += 2) gc.fillRect(l.xy[i], l.xy[i + 1], lado, lado);
            }
            vehiculosVisibles = visibles;
            return;
        }
        int etiquetasDibujadas = 0;
        int visibles = 0;
        for (EberthMPosicionVehiculo vehiculo : vehiculos) {
            // Perpendicular derecha del rumbo (y crece hacia abajo): sentidos opuestos
            // caen en lados opuestos de la línea central y forman dos carriles.
            double px = pantallaX(vehiculo.x()) - vehiculo.dirY() * DESFASE_CARRIL_VEHICULO;
            double py = pantallaY(vehiculo.y()) + vehiculo.dirX() * DESFASE_CARRIL_VEHICULO;
            boolean bus = vehiculo.tipo() == EberthMPosicionVehiculo.TipoVehiculo.BUS;
            double ancho = bus ? 18 : 14, alto = bus ? 10 : 8;
            if (!estaEnPantalla(px, py, ancho, alto)) continue;
            visibles++;
            gc.setFill(colorVehiculo(vehiculo));
            gc.fillRoundRect(px - ancho / 2, py - alto / 2, ancho, alto, 4, 4);
            gc.setFill(fondoClaro() ? Color.web("#263142") : Color.web("#17202A"));
            gc.fillRect(px - ancho / 3, py - alto / 2 + 1, ancho / 2, alto / 3);
            if (zoom >= 25) { // ruedas solo muy de cerca
                gc.setFill(fondoClaro() ? Color.web("#263142") : Color.BLACK);
                gc.fillOval(px - ancho / 3, py + alto / 4, 4, 4);
                gc.fillOval(px + ancho / 6, py + alto / 4, 4, 4);
            }
            boolean sobrecupo = vehiculo.tipo() == EberthMPosicionVehiculo.TipoVehiculo.MINIBUS
                    && vehiculo.pasajeros() > EberthMPosicionVehiculo.ASIENTOS_MICRO;
            // Zoom cercano: rótulo de sobrecupo; con zoom muy cercano, el de todos los micros.
            if ((zoom >= 40 || sobrecupo) && etiquetasDibujadas < 80) {
                String texto = vehiculo.nombreRuta() + " · " + vehiculo.pasajeros() + " pax";
                gc.setFont(FONT_VEHICULO);
                gc.setStroke(colorHalo);
                gc.setLineWidth(3);
                gc.strokeText(texto, px + 10, py - 5);
                gc.setFill(sobrecupo ? Color.RED : colorPuntoTexto);
                gc.fillText(texto, px + 10, py - 5);
                etiquetasDibujadas++;
            }
        }
        vehiculosVisibles = visibles;
    }

    private void prepararAgrupacionVehiculos() {
        columnasVehiculos = Math.max(1, (int) Math.ceil(
                canvas.getWidth() / TAMANO_CELDA_VEHICULOS));
        int filasVehiculos = Math.max(1, (int) Math.ceil(
                canvas.getHeight() / TAMANO_CELDA_VEHICULOS));
        int tamano = columnasVehiculos * filasVehiculos;
        if (cantidadVehiculosPorCelda.length != tamano) {
            cantidadVehiculosPorCelda = new int[tamano];
            colorVehiculosPorCelda = new Color[tamano];
        } else {
            Arrays.fill(cantidadVehiculosPorCelda, 0);
            Arrays.fill(colorVehiculosPorCelda, null);
        }
        for (EberthMPosicionVehiculo vehiculo : vehiculos) {
            double px = pantallaX(vehiculo.x());
            double py = pantallaY(vehiculo.y());
            if (px < 0 || py < 0 || px >= canvas.getWidth() || py >= canvas.getHeight()) continue;
            int columna = (int) px / TAMANO_CELDA_VEHICULOS;
            int fila = (int) py / TAMANO_CELDA_VEHICULOS;
            int indice = fila * columnasVehiculos + columna;
            if (indice < 0 || indice >= tamano) continue;
            if (cantidadVehiculosPorCelda[indice] == 0) {
                colorVehiculosPorCelda[indice] = colorVehiculo(vehiculo);
            }
            cantidadVehiculosPorCelda[indice]++;
        }
    }

    private Color colorVehiculo(EberthMPosicionVehiculo vehiculo) {
        return switch (vehiculo.estado()) {
            case SIN_RUTA -> Color.RED;
            case AVERIADO -> Color.ORANGE;
            case DETENIDO, EN_PARADA -> Color.GRAY;
            case EN_RUTA -> croquisActivo() ? COLOR_RUTA_ACTIVA
                    : vehiculo.idRuta().isBlank()
                    ? Color.DEEPSKYBLUE : color(vehiculo.color());
        };
    }

    /** Caché de colores: evita parsear el mismo hexadecimal miles de veces por fotograma. */
    private Color color(String hex) {
        Color c = cacheColores.get(hex);
        if (c == null) {
            try {
                c = Color.web(hex);
            } catch (IllegalArgumentException ex) {
                c = Color.GRAY;
            }
            cacheColores.put(hex, c);
        }
        return c;
    }

    private static boolean esPrincipal(String h) {
        if (h == null) return false;
        return switch (h) {
            case "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
                 "secondary", "secondary_link", "tertiary", "tertiary_link" -> true;
            default -> false;
        };
    }

    private boolean esSecundaria(String h) {
        return h.equals("residential") || h.equals("service") || h.equals("footway")
                || h.equals("path") || h.equals("steps") || h.equals("track")
                || h.equals("unclassified") || h.equals("living_street")
                || h.equals("pedestrian") || h.equals("cycleway");
    }

    private int indiceColorVia(String tipo) {
        if (tipo == null) return coloresVias.length - 1;
        return switch (tipo) {
            case "motorway", "motorway_link", "trunk", "trunk_link" -> 0;
            case "primary", "primary_link" -> 1;
            case "secondary", "secondary_link" -> 2;
            case "tertiary", "tertiary_link" -> 3;
            case "residential", "living_street" -> 4;
            case "service", "services" -> 5;
            case "footway", "path", "steps" -> 6;
            case "track" -> 7;
            default -> coloresVias.length - 1;
        };
    }

    private static List<List<PieroGVia>> crearGruposVias() {
        List<List<PieroGVia>> g = new ArrayList<>(COLORES_VIAS_OSCURO.length);
        for (int i = 0; i < COLORES_VIAS_OSCURO.length; i++) g.add(new ArrayList<>());
        return g;
    }

    // ==================================================================
    //  ZOOM Y ENCUADRE
    // ==================================================================

    private void aplicarNivelZoom(int nuevoNivel, double cx, double cy) {
        double nuevoZoom = ZOOMS[nuevoNivel];
        offsetX = cx - (cx - offsetX) * (nuevoZoom / zoom);
        offsetY = cy - (cy - offsetY) * (nuevoZoom / zoom);
        zoom = nuevoZoom;
        nivelZoom = nuevoNivel;
        redibujarMapa();
    }

    public void zoomIn() {
        if (!cargado) return;
        int n = Math.min(nivelZoom + 1, ZOOMS.length - 1);
        if (n != nivelZoom) aplicarNivelZoom(n, canvas.getWidth() / 2, canvas.getHeight() / 2);
    }

    public void zoomOut() {
        if (!cargado) return;
        int n = Math.max(nivelZoom - 1, 0);
        if (n != nivelZoom) aplicarNivelZoom(n, canvas.getWidth() / 2, canvas.getHeight() / 2);
    }

    public void resetVistaPublico() { resetVista(); }

    public void centrarEn(double lon, double lat, double zoomDeseado) {
        if (!cargado) return;
        double nx = lonANormX(lon);
        double ny = latANormY(lat);
        int mejor = nivelZoom;
        double mejorDif = Double.MAX_VALUE;
        for (int i = 0; i < ZOOMS.length; i++) {
            double dif = Math.abs(ZOOMS[i] - zoomDeseado);
            if (dif < mejorDif) { mejorDif = dif; mejor = i; }
        }
        zoom = ZOOMS[mejor];
        nivelZoom = mejor;
        double e = escalaBase * zoom;
        offsetX = canvas.getWidth() / 2.0 - nx * e;
        offsetY = canvas.getHeight() / 2.0 - ny * e;
        redibujarMapa();
    }

    /** Encuadra uno o varios trayectos. */
    public void enfocarTrayectos(List<LuisYTrayecto> trayectos) {
        if (!cargado || trayectos == null) return;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        boolean hay = false;
        for (LuisYTrayecto trayecto : trayectos) {
            if (trayecto == null) continue;
            for (PieroGEdge edge : trayecto.edges()) {
                hay = true;
                minX = Math.min(minX, Math.min(edge.origen.x, edge.destino.x));
                minY = Math.min(minY, Math.min(edge.origen.y, edge.destino.y));
                maxX = Math.max(maxX, Math.max(edge.origen.x, edge.destino.x));
                maxY = Math.max(maxY, Math.max(edge.origen.y, edge.destino.y));
            }
        }
        if (!hay) return;
        double ancho = Math.max(0.0005, maxX - minX);
        double alto = Math.max(0.0005, maxY - minY);
        double zoomMaximo = Math.min((canvas.getWidth() - 100) / (escalaBase * ancho),
                (canvas.getHeight() - 100) / (escalaBase * alto));
        int nivel = 0;
        for (int i = 0; i < ZOOMS.length; i++) {
            if (ZOOMS[i] <= zoomMaximo) nivel = i;
        }
        zoom = ZOOMS[nivel];
        nivelZoom = nivel;
        double centroX = (minX + maxX) / 2;
        double centroY = (minY + maxY) / 2;
        offsetX = canvas.getWidth() / 2.0 - centroX * escalaBase * zoom;
        offsetY = canvas.getHeight() / 2.0 - centroY * escalaBase * zoom;
        redibujarMapa();
    }

    public void enfocarTrayecto(LuisYTrayecto trayecto) {
        enfocarTrayectos(trayecto == null ? List.of() : List.of(trayecto));
    }

    public double[] pantallaANormalizado(double px, double py) {
        double e = escalaBase * zoom;
        return new double[]{(px - offsetX) / e, (py - offsetY) / e};
    }

    public double[] normalizadoALonLat(double nx, double ny) {
        return new double[]{
                proyector.getMinLon() + nx * proyector.getAnchoGeo(),
                proyector.getMaxLat() - ny * proyector.getAltoGeo()
        };
    }

    public double getZoom() { return zoom; }
    public int getTotalVias() { return vias == null ? 0 : vias.size(); }
    public PieroGGrafoVial getGrafo() { return grafo; }
    public List<LuisYPuntoImportante> getPuntos() { return List.copyOf(puntos); }

    // ==================================================================
    //  ESTADO (llamar desde el hilo de JavaFX)
    // ==================================================================

    private static void enFx(Runnable accion) {
        if (Platform.isFxApplicationThread()) accion.run();
        else Platform.runLater(accion);
    }

    public void setPuntos(List<LuisYPuntoImportante> lista) {
        List<LuisYPuntoImportante> copia = List.copyOf(lista);
        enFx(() -> {
            puntos.clear();
            puntos.addAll(copia);
            actualizarExtremosRutas();
            redibujarMapa();
        });
    }

    /** Gente de la demanda generada como {@code lon, lat} por punto; vacío la quita del mapa. */
    public void setGenteDemanda(double[] lonLat) {
        double[] copia = lonLat == null ? new double[0] : lonLat.clone();
        enFx(() -> {
            genteDemanda = copia;
            redibujar();
        });
    }

    public void setEventos(List<EberthMEventoTrafico> lista) {
        List<EberthMEventoTrafico> copia = List.copyOf(lista);
        enFx(() -> {
            eventos = copia;
            hayLluvia = copia.stream().anyMatch(e -> e.tipo() == EberthMTipoEvento.LLUVIA);
            hayAnimacionEventos = !viasTrancadera.isEmpty() || copia.stream()
                    .anyMatch(LuisYMapaRutasCanvas::esAnimado);
            redibujar();
        });
    }

    /** Eventos con animación propia (pulso del accidente, semáforo intermitente del policía). */
    private static boolean esAnimado(EberthMEventoTrafico e) {
        return e.tipo() == EberthMTipoEvento.ACCIDENTE || e.tipo().esPolicia();
    }

    /** Tramos calculados por el motor: cerrados (línea roja gruesa) y con trancadera (naranja). */
    public void setViasAfectadas(List<PieroGEdge> cerradas, List<PieroGEdge> trancaderas) {
        List<PieroGEdge> copiaCerradas = List.copyOf(cerradas);
        List<PieroGEdge> copiaTrancaderas = List.copyOf(trancaderas);
        enFx(() -> {
            viasCerradas = copiaCerradas;
            viasTrancadera = copiaTrancaderas;
            hayAnimacionEventos = !copiaTrancaderas.isEmpty() || eventos.stream()
                    .anyMatch(LuisYMapaRutasCanvas::esAnimado);
            redibujar();
        });
    }

    public void setTemaClaro(boolean claro) {
        enFx(() -> {
            temaClaro = claro;
            aplicarEstiloBase();
            redibujarMapa();
        });
    }

    /**
     * Fuente de la foto de simulación que lee el {@link AnimationTimer} en
     * cada fotograma. El mapa nunca recibe vehículos «empujados» por evento.
     */
    public void setFuenteVehiculos(Supplier<EberthMMotorSimulacion.Instantanea> fuente) {
        this.fuenteVehiculos = fuente;
        this.versionVehiculos = -1;
    }

    /** Fija una lista de vehículos (sin motor); útil para limpiar el mapa. */
    public void setVehiculos(List<EberthMPosicionVehiculo> lista) {
        List<EberthMPosicionVehiculo> copia = List.copyOf(lista);
        enFx(() -> {
            vehiculos = copia;
            redibujar();
        });
    }

    public void setRutasMicros(List<EberthMServicioMicro> rutas, Set<String> visibles) {
        List<EberthMServicioMicro> copia = List.copyOf(rutas);
        Set<String> visiblesCopia = Set.copyOf(visibles);
        enFx(() -> {
            rutasMicros = copia;
            rutasMicrosVisibles = visiblesCopia;
            if (microIdaVuelta != null) {
                // Mantiene la versión más reciente (p. ej. recalculada por un evento).
                microIdaVuelta = copia.stream().filter(r -> r.id().equals(microIdaVuelta.id()))
                        .findFirst().orElse(null);
            }
            actualizarExtremosRutas();
            redibujarMapa();
        });
    }

    /**
     * Activa el modo Ida/Vuelta para una micro ({@code null} vuelve a la vista
     * normal con todas las rutas marcadas).
     */
    public void setMicroIdaVuelta(EberthMServicioMicro micro) {
        enFx(() -> {
            microIdaVuelta = micro;
            actualizarExtremosRutas();
            redibujarMapa();
        });
    }

    /** Qué sentidos se muestran en el modo Ida/Vuelta y en los trayectos calculados. */
    public void setSentidosVisibles(boolean ida, boolean vuelta) {
        enFx(() -> {
            mostrarIda = ida;
            mostrarVuelta = vuelta;
            redibujarMapa();
        });
    }

    public void setEtiquetasPuntos(EtiquetasPuntos modo) {
        enFx(() -> {
            etiquetasPuntos = modo == null ? EtiquetasPuntos.AUTOMATICO : modo;
            redibujarMapa();
        });
    }

    public void setMostrarCalor(boolean mostrar) {
        enFx(() -> {
            mostrarCalor = mostrar;
            redibujar();
        });
    }

    public void setTrayectoPartida(LuisYTrayecto t) {
        enFx(() -> {
            trayectoPartida = t;
            actualizarExtremosRutas();
            redibujarMapa();
        });
    }

    public void setTrayectoRetorno(LuisYTrayecto t) {
        enFx(() -> {
            trayectoRetorno = t;
            actualizarExtremosRutas();
            redibujarMapa();
        });
    }

    /** Activa el aspecto de croquis (fondo blanco, calles grises, zonas, leyenda). */
    public void setModoCroquis(boolean activo) {
        enFx(() -> {
            modoCroquis = activo;
            aplicarEstiloBase();
            redibujarMapa();
        });
    }

    public boolean isModoCroquis() { return modoCroquis; }

    /** Define los nombres de zona que se rotulan en el croquis. */
    public void setZonas(List<ZonaCroquis> lista) {
        List<ZonaCroquis> copia = List.copyOf(lista);
        enFx(() -> {
            zonas = copia;
            zonasDefinidas = true;
            redibujarMapa();
        });
    }

    private boolean fondoClaro() {
        return temaClaro;
    }

    /** El croquis blanco solo se usa con tema claro; el tema oscuro conserva su fondo. */
    private boolean croquisActivo() {
        return modoCroquis && temaClaro;
    }

    private void aplicarEstiloBase() {
        if (croquisActivo()) {
            fondo = Color.WHITE;
            colorTitulo = Color.web("#4A4A4A");
            colorPuntoBorde = Color.WHITE;
            colorPuntoTexto = Color.web("#111111");
            colorHalo = Color.web("#FFFFFF", 0.9);
            colorCluster = Color.web("#3267B2");
        } else if (temaClaro) {
            fondo = Color.web("#F3F6FA");
            colorTitulo = Color.web("#263B5A");
            colorPuntoBorde = Color.WHITE;
            colorPuntoTexto = Color.web("#1C2638");
            colorHalo = Color.web("#FFFFFF", 0.9);
            colorCluster = Color.web("#3267B2");
        } else {
            fondo = FONDO_OSCURO;
            colorTitulo = Color.web("#A9C7FF");
            colorPuntoBorde = FONDO_OSCURO;
            colorPuntoTexto = Color.web("#E6EAF5");
            colorHalo = Color.web("#11111B", 0.85);
            colorCluster = Color.web("#89B4FA");
        }
        coloresVias = temaClaro ? COLORES_VIAS_CLARO : COLORES_VIAS_OSCURO;
        setStyle("-fx-background-color: " + aHex(fondo) + ";");
    }

    private static String aHex(Color c) {
        return String.format("#%02X%02X%02X", (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
    }

    // ------------------------------------------------------------------ croquis

    /** Calles grises finas, de grosor uniforme. */
    private void dibujarViasCroquis(GraphicsContext gc, List<PieroGVia> visibles) {
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        gc.setStroke(CROQUIS_CALLE);
        gc.setLineWidth(Math.clamp(0.5 + Math.sqrt(zoom) * 0.12, 0.6, 1.4));
        gc.beginPath();
        for (PieroGVia v : visibles) {
            if (v.projX == null || v.projY == null || v.projX.length < 2) continue;
            trazarVia(gc, v);
        }
        gc.stroke();
    }

    /** Puntea en gris las celdas urbanizadas; el patrón se mueve con el mapa. */
    private void dibujarZonasUrbanas(GraphicsContext gc, double w, double h) {
        if (zonaUrbana == null) return;
        double celda = escalaBase * zoom / CELDAS_URBANAS;
        if (celda < 2) return;
        int puntosPorLado = Math.max(2, (int) Math.round(celda / 6.0));
        double paso = celda / puntosPorLado;
        double lado = Math.clamp(paso * 0.2, 0.8, 1.6);
        gc.setFill(CROQUIS_TRAMADO);
        int c0 = Math.clamp((long) Math.floor((0 - offsetX) / celda), 0, CELDAS_URBANAS - 1);
        int c1 = Math.clamp((long) Math.floor((w - offsetX) / celda), 0, CELDAS_URBANAS - 1);
        int f0 = Math.clamp((long) Math.floor((0 - offsetY) / celda), 0, CELDAS_URBANAS - 1);
        int f1 = Math.clamp((long) Math.floor((h - offsetY) / celda), 0, CELDAS_URBANAS - 1);
        for (int fila = f0; fila <= f1; fila++) {
            for (int col = c0; col <= c1; col++) {
                if (!zonaUrbana[fila * CELDAS_URBANAS + col]) continue;
                double x0 = offsetX + col * celda;
                double y0 = offsetY + fila * celda;
                int i0 = Math.max(0, (int) Math.floor((0 - x0) / paso));
                int i1 = Math.min(puntosPorLado - 1, (int) Math.floor((w - x0) / paso));
                int j0 = Math.max(0, (int) Math.floor((0 - y0) / paso));
                int j1 = Math.min(puntosPorLado - 1, (int) Math.floor((h - y0) / paso));
                for (int j = j0; j <= j1; j++) {
                    for (int i = i0; i <= i1; i++) {
                        gc.fillRect(x0 + (i + 0.5) * paso - lado / 2,
                                y0 + (j + 0.5) * paso - lado / 2, lado, lado);
                    }
                }
            }
        }
    }

    /** Marca de agua de zona: gris muy tenue, en vertical. */
    private void dibujarNombresZonas(GraphicsContext gc) {
        if (zonas.isEmpty()) return;
        for (ZonaCroquis zona : zonas) {
            gc.save();
            gc.translate(pantallaX(lonANormX(zona.lon())), pantallaY(latANormY(zona.lat())));
            gc.rotate(-90);
            gc.setFont(FONT_MARCA_AGUA);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setTextBaseline(VPos.CENTER);
            gc.setFill(Color.gray(0.5, 0.3));
            gc.fillText(zona.nombre().toUpperCase(Locale.ROOT), 0, 0);
            gc.restore();
        }
    }

    /** Leyenda: barra horizontal en la esquina inferior izquierda. */
    private void dibujarLeyenda(GraphicsContext gc) {
        List<String> textos = new ArrayList<>();
        List<Color> colores = new ArrayList<>();
        List<Boolean> discontinuas = new ArrayList<>();
        if (microIdaVuelta != null) {
            textos.add(microIdaVuelta.nombre().toUpperCase(Locale.ROOT) + ":");
            colores.add(null);
            discontinuas.add(false);
            textos.add("IDA");
            colores.add(COLOR_IDA);
            discontinuas.add(false);
            textos.add("VUELTA");
            colores.add(COLOR_VUELTA);
            discontinuas.add(false);
        } else {
            long visibles = rutasMicros.stream()
                    .filter(r -> rutasMicrosVisibles.contains(r.id())).count();
            if (visibles > 0) {
                textos.add(visibles + (visibles == 1 ? " RUTA" : " RUTAS") + " · COLOR PROPIO");
                colores.add(null);
                discontinuas.add(false);
                textos.add("IDA");
                colores.add(colorPuntoTexto);
                discontinuas.add(false);
                textos.add("VUELTA");
                colores.add(colorPuntoTexto);
                discontinuas.add(true);
            }
            if (trayectoPartida != null || trayectoRetorno != null) {
                textos.add("PARTIDA");
                colores.add(COLOR_IDA);
                discontinuas.add(false);
                textos.add("RETORNO");
                colores.add(COLOR_VUELTA);
                discontinuas.add(false);
            }
        }
        if (croquisActivo() && !vehiculos.isEmpty()) {
            textos.add("UNIDAD EN MOVIMIENTO");
            colores.add(COLOR_RUTA_ACTIVA);
            discontinuas.add(false);
        }
        if (textos.isEmpty()) return;

        double margen = 10, relleno = 10, muestra = 22, hueco = 5, separacion = 14;
        double ancho = relleno * 2 - separacion;
        double[] anchos = new double[textos.size()];
        for (int i = 0; i < textos.size(); i++) {
            MEDIDOR.setFont(FONT_LEYENDA);
            MEDIDOR.setText(textos.get(i));
            anchos[i] = MEDIDOR.getLayoutBounds().getWidth();
            ancho += anchos[i] + separacion + (colores.get(i) == null ? 0 : muestra + hueco);
        }
        double x = margen;
        double y = canvas.getHeight() - ALTO_LEYENDA - margen;
        if (y < 0 || x + ancho > canvas.getWidth()) return;

        gc.setFill(FONDO_LEYENDA);
        gc.fillRoundRect(x, y, ancho, ALTO_LEYENDA, 8, 8);
        gc.setFont(FONT_LEYENDA);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        double cx = x + relleno, cy = y + ALTO_LEYENDA / 2;
        for (int i = 0; i < textos.size(); i++) {
            if (colores.get(i) != null) {
                Color muestraColor = colores.get(i) == colorPuntoTexto ? TEXTO_LEYENDA : colores.get(i);
                gc.setStroke(muestraColor);
                gc.setLineWidth(4);
                gc.setLineCap(StrokeLineCap.BUTT);
                if (discontinuas.get(i)) gc.setLineDashes(5, 4);
                gc.strokeLine(cx, cy, cx + muestra, cy);
                gc.setLineDashes();
                cx += muestra + hueco;
            }
            gc.setFill(TEXTO_LEYENDA);
            gc.fillText(textos.get(i), cx, cy);
            cx += anchos[i] + separacion;
        }
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.BASELINE);
    }

    private static DensidadUrbana calcularDensidadUrbana(List<PieroGVia> vias) {
        int n = CELDAS_URBANAS;
        double[] largo = new double[n * n];
        for (PieroGVia v : vias) {
            if (v.projX == null || v.projY == null || v.projX.length < 2) continue;
            for (int i = 1; i < v.projX.length; i++) {
                double ax = v.projX[i - 1], ay = v.projY[i - 1];
                double bx = v.projX[i], by = v.projY[i];
                int col = Math.clamp((long) Math.floor((ax + bx) / 2 * n), 0, n - 1);
                int fila = Math.clamp((long) Math.floor((ay + by) / 2 * n), 0, n - 1);
                largo[fila * n + col] += Math.hypot(ax - bx, ay - by);
            }
        }
        boolean[] densa = new boolean[n * n];
        double sx = 0, sy = 0, peso = 0;
        for (int fila = 0; fila < n; fila++) {
            for (int col = 0; col < n; col++) {
                double suma = 0;
                for (int df = -1; df <= 1; df++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        int f = fila + df, c = col + dc;
                        if (f >= 0 && f < n && c >= 0 && c < n) suma += largo[f * n + c];
                    }
                }
                if (suma < UMBRAL_URBANO) continue;
                densa[fila * n + col] = true;
                double w = largo[fila * n + col];
                sx += (col + 0.5) / n * w;
                sy += (fila + 0.5) / n * w;
                peso += w;
            }
        }
        return new DensidadUrbana(densa, peso > 0 ? sx / peso : 0.5, peso > 0 ? sy / peso : 0.5);
    }

    public void setOnMapaCargado(Runnable cb) {
        onMapaCargado = cb;
        if (cargado && cb != null) cb.run();
    }

    public void setOnInfoMapa(java.util.function.Consumer<String> cb) {
        onInfoMapa = cb;
        ultimaInfoMapa = null;
        viasNecesitanRedibujarse = true;
    }

    public void setOnError(java.util.function.Consumer<String> cb) { onError = cb; }
    public void setOnClic(java.util.function.BiConsumer<Double, Double> cb) { onClic = cb; }

    /** Doble clic con {@code {lon, lat}}; devuelve {@code true} si lo consumió (no se reencuadra). */
    public void setOnDobleClic(java.util.function.Predicate<double[]> cb) { onDobleClic = cb; }

    private double[] pantallaALonLat(double x, double y) {
        double[] norm = pantallaANormalizado(x, y);
        return normalizadoALonLat(norm[0], norm[1]);
    }

    public void setOnMouseMoveCallback(java.util.function.Consumer<double[]> cb) {
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, e -> {
            if (!cargado) return;
            double[] n = pantallaANormalizado(e.getX(), e.getY());
            cb.accept(normalizadoALonLat(n[0], n[1]));
        });
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, e -> {
            if (!cargado) return;
            double[] n = pantallaANormalizado(e.getX(), e.getY());
            cb.accept(normalizadoALonLat(n[0], n[1]));
        });
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        redibujar();
    }

    public void cerrar() {
        generacionCarga.incrementAndGet();
        renderizador.stop();
    }
}
