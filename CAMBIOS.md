# Cambios de la refactorización (UI/UX, eventos y rendimiento)

## Fase 1 · Datos por ciudad
- `mapas/juliaca|puno|lima/` con `calles.geojson` (solo calles) y `puntos.json`
  (POIs) separados. `datos/puntos.json` pasó a `mapas/juliaca/puntos.json`.
- `PieroGCiudadMapa` (catálogo) y `BetoHFuenteMapa` (busca en `./mapas`, `~/.trafiqueishon/mapas`
  y el jar; admite `.gz`). Selector **Ciudad** en la barra superior.
- Rutas guardadas por ciudad (`BetoHRepositorioServiciosMicro.paraCiudad`).
- Los tipos de `puntos.json` (EDUCACION, SALUD…) ya no se pierden como PARADA.
- `generar_puntos.py` salió de `resources` (se empaquetaba en el jar) a `herramientas/`.

## Fase 2 · Interfaz
- Accordion: Mapas · Rutas y Puntos · Simulación · Eventos · Exportar.
- Tipografía 16/14/12 px y contraste revisado en tema oscuro.
- LOD de POIs: culling, clústeres con contador, nombres desde zoom 4 sin solaparse.
- Buscador de puntos (también en el diálogo de rutas) y colores por categoría.
- Tab/Espacio ya no se disparan mientras se escribe en un campo de texto.

## Fase 3 · Rendimiento
- `EberthMMotorSimulacion`: simulador en hilo propio con foto inmutable (`Instantanea`).
- `EberthMAplicadorEventos`: eventos sobre el grafo con prefiltro por caja (en el motor).
- `LuisYMapaRutasCanvas`: AnimationTimer hasta 60 FPS con presupuesto adaptativo,
  culling de todas las capas, LOD de calles en mapas grandes, paneo por
  desplazamiento de la capa de calles.
- Cálculo de rutas/trayectos/PDF en el hilo `calculo-rutas`.
- Memoria: `PieroGCoordenadasCompactas`, rejilla CSR de nodos y mapa `long→int` en
  `PieroGGrafoVial` (antes `HashMap<SnapKey,Integer>` y STRtree con `Envelope` por nodo).

## Fase 4 · Vistas y exportación
- Checkboxes por micro (vista múltiple, ida continua / vuelta discontinua).
- Modo Ida/Vuelta: solo la micro elegida, ida azul y vuelta roja con flechas.
- `DanielTExportadorPdf.exportarVistaMultiple` y `exportarVistaIndividual`
  (se mantiene `exportar` = una lámina por micro).

## Decisiones tomadas
- **Paleta unificada**: había dos `LuisYPaletaColores` contradictorias (UI: ida azul;
  rutas/PDF: ida roja). Queda solo `trafiqueishon.ruta.LuisYPaletaColores` con
  ida azul / vuelta roja, como pide la especificación.
- **Lima no se incluye en el jar**: un GeoJSON de cientos de MB inflaría el
  build y el repositorio. Se lee desde carpeta externa (`/mapas/` está en `.gitignore`).
- **No se añadieron mapas de Puno/Lima inventados**: sin datos reales, la app
  avisa dónde colocar el archivo (Ayuda → «¿Dónde poner los mapas…?»).
- **Paso de simulación a 30 Hz**: suficiente para el movimiento y más liviano en
  equipos de 2 GiB; el render sí llega a 60 FPS en zoom/paneo.
- **Prueba PDF corregida**: `pdfIncluyeUnaLeyendaYSePuedeAbrir` esperaba 2 páginas
  para 9 micros distintas y fallaba ya antes de estos cambios; se reemplazó por
  pruebas de los tres modos de exportación.

## Corregido de paso
- Un clic al terminar un arrastre del mapa ya no coloca puntos/eventos por error.
- `PieroGVia.liberarCoords()` ya no llama a `clear()` (fallaba con listas inmutables).

## Autogenerador de rutas reescrito (demanda → troncales y alimentadoras)
- **Demanda centralizada**: se quitó el botón «Generar Demanda de la Ciudad». La demanda
  proyectada es el Paso 1 del diálogo «Autogenerar Rutas» (se calcula al abrir, se resume
  «Juliaca: 182,000 viajes/día. Pico: … a las 7 AM», se guarda en memoria y el panel de
  Simulación la muestra). El generador usa esa misma demanda como base.
- **Diálogo en 4 pasos**: demanda · cobertura objetivo (slider 50/80/100 %, actualiza las
  rutas necesarias) · tipos (troncales / alimentadoras) · confirmación. Al aceptar:
  «Se generaron X rutas. Cobertura alcanzada: Y %»; bajo el 50 % sale la alerta de cobertura.
- **Troncales (80 %)**: de un extremo de la ciudad al opuesto, ≥ 8 km (hasta ≈ 12 km),
  por 3-4 POIs populares (mercados, hospitales y los más concurridos).
  **Alimentadoras (20 %)**: periferia → POI central popular, 4-6,5 km.
- **Validación**: una ruta bajo 4 km se descarta; la longitud se estima antes de trazar con
  un factor de rodeo que se aprende de cada trazado real, y las siguientes candidatas se
  buscan más lejos. Una ruta sin POIs populares junto al trazado se descarta; si el mapa no
  permite 3 POIs, se relaja a 2 y luego a 1 (nunca a 0).
- **Flota**: `unidadesParaRuta` = máx(unidades que exige la demanda de la ruta en hora punta,
  vuelta ÷ frecuencia normal), sin pasar de vuelta ÷ frecuencia de punta. Las rutas largas
  llevan más unidades. Los viajes que ya cubría otra ruta cuentan a medias.
- Medido con Juliaca real (calles + 412 puntos): antes 1,3-5,6 km y 15 % de cobertura con 10
  rutas; ahora troncales 8,4-12,6 km, alimentadoras 5,1-6,8 km, 32 % con 10 rutas y ≈ 51 %
  con 40. La demanda de Juliaca está muy dispersa, por eso el 80/100 % no se alcanza con
  las calles y puntos cargados y el diálogo informa el máximo posible.

## Simulación conectada con la demanda (inyección, paradas y abordaje)
- **Demanda siempre inyectada**: al iniciar la simulación se proyecta la demanda de la ciudad aunque
  no se haya abierto «Autogenerar Rutas» (antes, sin ella, la gente nacía en modo uniforme y casi no
  había pasajeros). La escala por defecto pasa de ×1 a ×10 (un pasajero simulado = 10 habitantes):
  con ×10 se inyecta la demanda real (≈ 250 viajes/min en la punta de Juliaca).
- **Rutas por las zonas de origen**: `EberthMGeneradorRutas.paradasEnZonas` pone una parada sobre el
  trazado en cada zona con demanda a ≤ 250 m (hasta 45 paradas). Antes solo había 5-6 paradas y el
  simulador no genera gente a más de 300 m de una parada («zonas sin cobertura»).
- El generador también premia las zonas de origen sin ruta, y la flota se dimensiona con la demanda
  real repartiendo el tope de 500 unidades entre las rutas.
- **Abordaje universal**: quien espera sube a cualquier micro con sitio (hasta un 20 % de sobrecupo);
  si no lo lleva a su destino baja en el punto de la ruta más cercano y hace transbordo. Quien ya hizo
  un transbordo sube si el micro lo acerca ≥ 150 m.

## Mapa fluido con 50+ rutas (LOD y capa de rutas)
- **Rutas en la capa cacheada**: antes `dibujarRutas` recorría en cada fotograma todos los tramos de
  todas las rutas (doble trazo). Ahora se pintan en la capa de calles, que solo se repinta al
  cambiar el zoom, las rutas o tras un arrastre largo; el fotograma vivo solo dibuja micros y
  superposiciones. Con zoom lejano se omiten los vértices a < 2 px, el borde, los guiones y la vuelta.
- **LOD por zoom**: lejano (< 5) solo rutas y micros de 1 px; medio (5-15) suma semáforos, eventos,
  vías cerradas, gente y paradas; cercano (> 15) suma nombres, policías y rótulos de sobrecupo.
- **Zonas sin cobertura**: los círculos grises solo se dibujan con zoom > 10; antes basta el
  contador «Zonas sin cobertura» del panel de estadísticas.
- **Micros por lotes**: se agrupan por color y se pintan con un `setFill` por color (rectángulos de
  1 o 4 px); las ruedas (óvalos) solo con zoom ≥ 25. La gente de la demanda usa rectángulos.
- El plan del autogenerador admite hasta **50 rutas** (`MAX_RUTAS_PLAN`); el contador de cobertura
  del diálogo sale del plan real (`coberturaAcumulada`).

## Optimización para Lima · Fases 1 y 2
- Sección «Rutas y Micros» unificada: lista de rutas, vista Ida/Vuelta compacta (combo + interruptor + 2 casillas) y micros de la ruta elegida (refresco 1 s solo con la sección abierta).
- Lluvia en color sólido translúcido (sin `RadialGradient`); bordes de 1 px; no había `DropShadow`.
- Capa estática de calles con `setCache(true)` + `CacheHint.SPEED`; márgenes de culling al 5 %.
- LOD: < 5 micros de 1 px y rutas de 1 px, sin semáforos/eventos/textos; 5–15 micros 3x3, semáforos como puntos, sin textos; > 15 todo.
- Solo se dibujan los 10 eventos más cercanos al centro de la cámara.

## Optimización para Lima · Fases 3 y 4
- `EberthMMotorSimulacion`: el paso del reloj se parte en ticks de ≤ 1 s simulado (a x40, ~40 ticks/s) con un presupuesto de 60 ms por paso; el `AnimationTimer` solo lee la última foto. Velocidad máxima 40x.
- Barra de estado con FPS reales (pulsos de JavaFX, `System.nanoTime`, cada 0,5 s).
- Botón «Modo Estrés» (panel Simulación): eventos orgánicos + hora punta + x40 + Lima si existe; mide 10 s y avisa «¡Prueba superada!» con > 20 FPS.
- Pulse logger: `mvn javafx:run -Dapp.pulse=true` (o `java -Djavafx.pulseLogger=true …`).
