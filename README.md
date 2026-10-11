# Trafiqueishon - simulador urbano

Aplicacion de escritorio JavaFX para cargar redes viales GeoJSON, calcular rutas
y ejecutar escenarios urbanos. Juliaca es el mapa incluido por defecto; se
pueden cargar redes de otras ciudades.

## Funcionalidad

- Mapa interactivo desde `mapas/juliaca.geojson` o un archivo GeoJSON externo
  (`LineString` y `MultiLineString`), con zoom, desplazamiento, filtros y
  seleccion de origen/destino. La ultima red elegida se recuerda localmente.
- Busqueda de rutas con A* o Dijkstra y calculo de distancias geodesicas
  aproximadas en metros.
- Simulacion de una flota configurable que viaja entre los puntos de
  `datos/poi.json`, ponderados por el campo `peso`. Los vehiculos avanzan por la
  red y la velocidad cambia segun la ocupacion estimada de cada tramo. El
  escenario de Juliaca incluye Plaza de Armas y los accesos Cusco, Puno y
  Huancane para distribuir viajes urbanos e interurbanos.
- Controles para iniciar, pausar, reanudar y reiniciar la simulacion; velocidad
  configurable entre 1x y 20x.
- Metricas de vehiculos activos, viajes completados, hora simulada, velocidad
  media y tramos congestionados.
- Flota de hasta 50.000 agentes con estado en arreglos primitivos y snapshots
  compactos; vias y tramos del mapa se filtran por viewport usando STRtree.
- Motor en hilo separado, con integracion IDM por carril, aceleracion/frenado
  dependiente del lider, parada ante semaforos y adelantamiento local de
  mototaxis cuando hay mas de un carril.
- Renderizado de vehiculos y semaforos en el mismo Canvas del mapa, con formas
  agrupadas por tipo y snapshots consumidos por el bucle JavaFX. No se crean
  nodos JavaFX por vehiculo.
- Mapa de calor alternable que colorea los tramos con su factor de saturacion:
  verde (flujo libre), amarillo (moderado) y rojo (alto).
- Rejilla espacial de arreglos primitivos para consultas de proximidad y
  particionamiento de agentes por pantalla. El seguimiento de carril utiliza
  ordenamiento de indices, no comparaciones de todos contra todos.
- Semaforos candidatos puntuados con
  `P(v) = 1.5*Degree + 4*Hierarchy + 2*Volume`: grado minimo 3, dos o mas
  aproximaciones principales, umbral 14.5, separacion minima de 500 m y
  maximo de 60. El volumen es estimado con la matriz OD. Control opcional
  Q-learning con colas por eje y fases acotadas; no es un inventario ni un plan
  semaforico validado para despliegue vial.
- Matriz origen-destino construida desde POI agrupados con k-means; los pesos
  de POI son supuestos de escenario y se deben sustituir por aforos reales.
- Tasas iniciales (vehiculos/minuto) de 16 autos, 12 motos lineales, 20
  mototaxis, 2 de carga y 0.5 trailers; son parametros de escenario ajustables,
  no aforos medidos. Las rutas de micro se despachan cada 8 minutos por defecto
  y cada clase aplica sus propios tipos de via permitidos.
- Los vehiculos iniciales y los generados por demanda salen de hasta 24 accesos
  perifericos detectados en los nodos extremos de la red; cada clase usa solo
  accesos con rutas permitidas hacia puntos de interes.
- El panel de trafico permite restaurar semaforos sugeridos por grado, jerarquia
  y densidad de aproximaciones principales, y activar el modo de quitar uno al
  hacer clic sobre su interseccion en el mapa. Autos, micros y pesados usan
  colores cian, amarillo y magenta de alta visibilidad; el tamaño conserva un
  minimo en pantalla y crece moderadamente con el zoom (0,5x a 48x).
- Alta manual de referencias con nombre. Gestion de rutas de micros en ventana
  independiente, con orden editable de paradas, frecuencia, flota y seleccion
  de logo. Las rutas y logos se guardan en `~/.trafiqueishon/`. Cada recorrido
  puede incluir varios puntos intermedios obligatorios.
- Importacion de rutas desde libros `.xlsx`: cada hoja representa una empresa;
  se omiten las hojas vacias y se informan los errores por fila. Se incluye una
  plantilla descargable y se admiten celdas con formulas.
- Filtro de empresas para mostrar en el mapa solo las unidades activas de la
  flota elegida. La ruta vial calculada se convierte en un corredor preferido
  para los viajes nuevos; no cambia los trayectos de unidades que ya circulan.
- Selector de tema claro u oscuro, disponible tambien en la ventana de gestion
  de micros. El zoom del mapa abarca de 0,5x a 48x.
- Panel lateral unificado en acordeon para mapa, rutas, puntos, trafico,
  metricas y escenario. Los selectores muestran nombres legibles.
- Persistencia JSON automatica para referencias, rutas y preferencias
  (demanda, flota y velocidad); el archivo de red seleccionado se recuerda.
- Reporte PDF exportable desde la gestion de micros, con logo opcional, paradas
  y coordenadas, frecuencia, flota, distancia Dijkstra y resumen operativo.

Los agentes se guardan en arreglos contiguos reutilizables (structure of
arrays): al llegar al destino, el ultimo slot activo se intercambia con el
retirado. Este diseño evita objetos Java por agente y asignaciones por vehiculo
en cada tick, por lo que se prefiere a un pool de objetos individuales.

La simulacion es un modelo inicial para prototipado: velocidades, capacidad,
volumen OD y prioridades de interseccion son estimaciones, no datos medidos en
campo. El IDM sigue vehiculos en la misma arista y carril; no sustituye un
simulador microscopico validado de conflictos de cruce. El Q-learning no ha sido
entrenado ni validado con series historicas. El limite de 50.000 es de agentes
en el modelo, no una garantia de FPS; perfilar en el hardware objetivo antes de
prometer capacidad comercial.

## Requisitos y ejecucion

- JDK 21
- Maven 3.8+

Ejecutar:

```sh
mvn javafx:run
```

Construir:

```sh
mvn test
mvn package
```

En IntelliJ IDEA, importa el proyecto Maven y ejecuta la configuracion
`javafx:run`.

## Simulacion

1. Espera a que se carguen la red y el escenario, o usa **Abrir GeoJSON**.
2. En el acordeon lateral, abre **Trafico** para definir la flota inicial, limite simultaneo, tasas por tipo y
   velocidad de simulacion. La matriz indica las vias permitidas por categoria.
3. Inicia. Los vehiculos aparecen sobre el mapa y recorren rutas entre los POI.
4. Consulta **Metricas** para ver actividad y congestion; pausa o reinicia cuando
   sea necesario.

Abre **Gestion de micros** para agregar una ruta seleccionando origen/destino,
agregando las paradas y ordenandolas con **Subir/Bajar**, y definiendo frecuencia,
unidades activas y logo opcional. Selecciona una linea de la tabla para generar
su reporte PDF. Tambien puedes importar o crear una plantilla Excel: agrega una
hoja por empresa y completa las columnas `Ruta`, `Origen`, `Destino`, `Puntos
intermedios`, `Tipo de vehiculo`, `Frecuencia (minutos)` y `Unidades`. Los puntos
se resuelven por identificador o nombre y se recorren en el orden indicado; para
separarlos se aceptan comas, punto y coma, el signo `>` o un guion rodeado por
espacios. La columna de puntos intermedios puede quedar vacia. El tipo admitido
es `Micro` o `Transporte publico`; las frecuencias y cantidades deben ser
enteros positivos. Las filas invalidas se reportan y no impiden importar otras
filas validas del mismo libro.

Al calcular una ruta vial con Dijkstra o A*, sus aristas pasan a ser un corredor
preferido para viajes generados posteriormente. El filtro de empresas del mapa
permite aislar las unidades activas de una flota. El selector `Tema` alterna
entre modo claro y oscuro y aplica el estilo a la ventana principal y a la
gestion de micros. En **Puntos**, escribe el nombre de un lugar y haz clic sobre
el mapa para crear una referencia persistente; queda disponible como origen,
destino o parada. Agregar un punto reinicia la simulacion para reconstruir las
rutas.

La flota admite hasta 50.000 agentes. La preparacion de rutas configuradas se
ejecuta en segundo plano para evitar bloquear la interfaz. El motor usa un
scheduler independiente y el Canvas limita el renderizado a 30 FPS; se dibujan
solo los elementos que intersectan el viewport y se reutilizan grupos de vias.
El limite de FPS no es una garantia de rendimiento en cualquier hardware. El IDM aplica
`a = a_max * (1 - (v/v0)^4 - (s*/s)^2)`, con
`s* = s0 + max(0, v*T + v*(v-v_lider)/(2*sqrt(a_max*b)))`. Los parametros por
tipo de vehiculo son valores iniciales no calibrados.

## Estructura

```text
src/main/java/trafiqueishon/
├── Main.java
├── geo/
│   ├── GeoJsonParser.java (recursos y archivos externos)
│   ├── Proyector.java
│   ├── GrafoVial.java
│   ├── Nodo.java / Edge.java / Via.java
│   ├── Pathfinder.java
│   └── RutaResult.java
├── simulacion/
│   ├── CargadorPuntosInteres.java / PersistenciaEscenario.java
│   ├── PuntoInteres.java / RutaMicro.java
│   ├── TipoVehiculo.java / DemandaVehicular.java
│   ├── ModeloIDM.java / PoolVehiculos.java / SpatialGrid.java
│   ├── MatrizDemandaOD.java / PrioridadSemaforo.java
│   ├── SemaforoInteligente.java / ReporteRutaPdf.java
│   └── SimuladorTrafico.java
└── ui/
    ├── MainView.java
    ├── MapaCanvas.java
    └── SimulacionController.java
```

El GeoJSON incluido proviene de OpenStreetMap. Debe conservarse la atribucion y
respetarse la licencia ODbL indicada en sus metadatos. Las redes importadas
deben conservar su propia atribucion y licencia.
