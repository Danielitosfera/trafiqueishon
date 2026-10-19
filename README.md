# Trafiqueishon — generador y simulador de rutas

Aplicación de escritorio JavaFX para visualizar la red vial de **Juliaca, Puno o
Lima**, editar puntos de interés, definir rutas de micros, simular sus salidas
programadas con eventos de tráfico y exportar las rutas en PDF estilo croquis.

## Ejecutar

Requiere Java 21 y Maven:

```sh
mvn test
mvn javafx:run                  # Juliaca / Puno (768 MiB de heap)
mvn javafx:run -Dapp.xmx=2g     # Lima u otros mapas masivos
```

El heap por defecto (`app.xmx=768m`) deja memoria al sistema en equipos con
2 GiB de RAM. JavaFX intenta usar el pipeline ES2 de la GPU y conserva el
pipeline software como alternativa.

## Mapas por ciudad

```
src/main/resources/mapas/
├── juliaca/  calles.geojson  puntos.json
├── puno/     puntos.json     (falta calles.geojson)
└── lima/     puntos.json     (falta calles.geojson)
```

- `calles.geojson` contiene **solo calles** (`LineString`/`MultiLineString`).
- `puntos.json` contiene los **puntos de interés** que se dibujan encima:
  `{"puntos":[{"id","nombre","x"(lon),"y"(lat),"tipo"}]}`. El `tipo`
  (`EDUCACION`, `SALUD`, `MERCADO`, `TRANSPORTE`, `PLAZA`, …) colorea el marcador.
- El selector **Ciudad** de la barra superior recarga calles, puntos, rutas
  guardadas y eventos de la carpeta elegida. Si falta `calles.geojson`, la app
  avisa dónde colocarlo en lugar de mostrar un mapa vacío.
- Búsqueda (en orden): `./mapas/<ciudad>/`, `~/.trafiqueishon/mapas/<ciudad>/`
  y por último el jar. **Lima debe ir fuera del jar** (pesa cientos de MB);
  se acepta `calles.geojson.gz`. Ver `src/main/resources/mapas/LEEME.md` para
  descargarla desde overpass-turbo.
- Las rutas de micros se guardan por ciudad: `~/.trafiqueishon/rutas-micros.json`
  (Juliaca, archivo histórico) y `rutas-micros-<ciudad>.json` (las demás).
- `herramientas/generar_puntos.py` crea un `puntos.json` inicial a partir de
  las avenidas con nombre de un GeoJSON.

## Interfaz

Panel lateral en **Accordion** con cinco secciones plegables:

| Sección | Contenido |
|---|---|
| **Mapas** | Datos de la red cargada, recargar/encuadrar, vista croquis, mapa de calor y modo de nombres de puntos (automático, siempre, ocultos). |
| **Rutas y Puntos** | Micros con **checkbox** para verlas a la vez (cada una con su color), modo **Ida/Vuelta**, buscador y edición de puntos de interés, importación JSON/Excel y trayecto que recorre todos los puntos. |
| **Simulación** | Hora inicial, iniciar/pausar/continuar/detener, estadísticas en vivo y demanda de pasajeros. |
| **Eventos** | Trancaderas, cierres, accidentes, lluvia, etc. sobre la vía clicada. |
| **Exportar** | PDF vista múltiple, PDF vista individual y otros formatos. |

Tipografía: títulos de sección 16 px negrita, botones y texto base 14 px,
textos secundarios y coordenadas 12 px, con contraste AA en ambos temas.
**Tab** oculta el panel, **F11** activa el modo presentación, **Esc** sale y
**Espacio** inicia/continúa (ninguno se dispara mientras se escribe en un campo).

### Vistas de rutas

- **Vista múltiple** (normal): todas las micros marcadas con su color propio;
  la ida en línea continua y la vuelta discontinua, ligeramente desplazadas
  para que se vean ambas en calles compartidas.
- **Modo Ida/Vuelta**: elige una micro (selector o botón ⇄ de su fila) y activa
  el modo. El mapa oculta las demás rutas y muestra solo su **ida en azul** y su
  **vuelta en rojo**, con flechas de sentido, marcadores INICIO/FINAL y el panel
  de paraderos. Las casillas *Ida* / *Vuelta* ocultan cada sentido.
- Los colores de ida/vuelta salen de `trafiqueishon.ruta.LuisYPaletaColores`, única
  fuente para mapa, leyenda y exportadores.

### Puntos de interés (LOD)

- Solo se dibujan los puntos dentro del área visible.
- Con zoom lejano, los puntos que caen en la misma celda de pantalla se agrupan
  en un **clúster** con su cantidad; al acercarse se separan.
- Los nombres aparecen desde zoom 4, con halo y **sin encimarse** (rejilla de
  colisiones). Los inicios/finales de ruta siempre se rotulan.

## Rendimiento y hilos

- **Motor de simulación en hilo propio** (`EberthMMotorSimulacion`): el simulador y los
  costos del grafo solo se modifican en el hilo `motor-simulacion`, que avanza
  30 pasos por segundo y publica una foto inmutable del estado. Eventos de
  tráfico, hora punta y desvíos A* se aplican allí, sin congelar la interfaz.
- **Render hasta 60 FPS** (`LuisYMapaRutasCanvas`): un `AnimationTimer` lee esa foto
  en cada fotograma; el mapa nunca se repinta por evento individual. Si un
  fotograma tarda más de 16 ms, los siguientes se espacian automáticamente.
- **Viewport culling**: calles por STRtree con la caja visible; rutas, puntos,
  eventos, cierres y vehículos por caja de pantalla. En mapas grandes con zoom
  lejano solo se dibujan vías principales y se omiten las de tamaño subpíxel.
- **Paneo instantáneo**: al arrastrar se desplaza la imagen de calles ya
  pintada y se repinta al soltar.
- **Cálculos fuera del hilo de JavaFX** (`calculo-rutas`): crear/restaurar rutas,
  trayectos y exportaciones PDF.
- **Memoria para mapas masivos**: coordenadas empaquetadas en `double[]`
  durante la carga, índice de nodos en rejilla primitiva (CSR) en lugar de
  STRtree y mapa `long→int` sin objetos para unir vértices. Prueba con un mapa
  sintético de 250 000 calles / 2,5 M nodos / 4,5 M tramos: el código anterior
  no cargaba con 1 GiB y retenía ~1,15 GiB; ahora carga con 1 GiB y retiene
  ~830 MiB.

## Simulación

- Las micros salen desde ambos terminales según su frecuencia hasta completar la
  flota (máximo 6 000 unidades en total). Hasta 18 pasajeros por salida; espera
  de ~15 s en paraderos y un intervalo en el terminal antes de volver.
- El reloj simulado incrementa el tráfico entre 07:00–09:00, 12:00–14:00 y
  17:00–20:00, y la demanda (mañana 2.5x, mediodía 1.8x, tarde 2.8x, noche 0.2x).
- Eventos: lluvia, trancaderas y semáforos alteran la velocidad; una gasolinera
  detiene la unidad tres minutos simulados; cierres, accidentes y
  manifestaciones bloquean la vía y recalculan desvíos con A* sin detener la
  simulación.
- Al detener aparece un resumen con abordajes, ocupación media, incidencias y
  tiempo simulado.

## Exportación

- **PDF · Vista múltiple**: una lámina con todas las micros marcadas, cada una
  con su color (ida continua, vuelta discontinua), panel con la lista de rutas
  y sus kilómetros, sobre el croquis blanco con calles grises.
- **PDF · Vista individual**: una lámina con la ida (azul) y la vuelta (roja)
  de la micro seleccionada y sus paraderos.
- **Otros formatos**: GeoJSON, GPX, CSV, TXT (puntos en orden con coordenadas)
  o PDF con una lámina por micro, eligiendo rutas y sentidos.

## Alcance actual

Los eventos solo duran mientras la aplicación está abierta. El modelo de
pasajeros no asigna origen/destino por persona ni simula ascensos y descensos en
cada parada. La generación de demanda es una aproximación configurable, no usa
mediciones de tráfico en vivo. Los vehículos se actualizan a 30 Hz (paso del
motor) aunque el mapa se pinte a 60 FPS al hacer zoom o paneo.
