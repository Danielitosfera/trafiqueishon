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
