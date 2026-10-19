# Mapas por ciudad

Cada ciudad vive en su propia carpeta con dos archivos:

| Archivo          | Contenido                                                        |
|------------------|------------------------------------------------------------------|
| `calles.geojson` | Solo geometrías de calles (`LineString` / `MultiLineString`, OSM). |
| `puntos.json`    | Puntos de interés: `{"puntos":[{"id","nombre","x"(lon),"y"(lat),"tipo"}]}` |

Tipos de punto reconocidos (colorean el marcador): `TERMINAL`, `TRANSPORTE`,
`EDUCACION`, `SALUD`, `MERCADO`, `COMERCIO`, `PLAZA`, `PARQUE`, `TURISMO`,
`DEPORTE`, `INSTITUCION`, `ORIGEN`, `DESTINO`, `PARADA`. Cualquier otro valor se
lee como `PARADA`.

## Dónde busca la aplicación (en este orden)

1. `./mapas/<ciudad>/` junto al directorio desde el que se ejecuta la app.
2. `~/.trafiqueishon/mapas/<ciudad>/`
3. Dentro del jar: `src/main/resources/mapas/<ciudad>/`

**Lima no debe empaquetarse dentro del jar**: su GeoJSON pesa cientos de MB.
Colócalo en la opción 1 o 2 y ejecuta con más memoria:

```sh
mvn javafx:run -Dapp.xmx=2g
```

## Descargar calles desde overpass-turbo.eu

Exporta como GeoJSON. Para Lima conviene excluir veredas y caminos peatonales
(reduce el archivo a menos de la mitad):

```
[out:json][timeout:300];
area["name"="Lima"]["admin_level"="4"]->.a;
way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service)(_link)?$"](area.a);
out geom;
```

Si overpass no encuentra el área, búscala por nombre en el mapa de overpass-turbo
y ajusta `name`/`admin_level`. Para Puno cambia el área por `area["name"="Puno"]["admin_level"="8"]` y puedes
usar `way["highway"]` sin filtro.
