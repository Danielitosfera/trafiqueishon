"""Genera un puntos.json de partida a partir de las calles con nombre de un GeoJSON.

Uso:
    python herramientas/generar_puntos.py src/main/resources/mapas/puno/calles.geojson \
        src/main/resources/mapas/puno/puntos.json

Toma el primer vértice de avenidas y vías principales con nombre como PARADA.
Revisa y depura el resultado a mano: el GeoJSON de calles no trae lugares
(colegios, mercados...), así que las categorías deben completarse después con
los tipos reconocidos (TERMINAL, EDUCACION, SALUD, MERCADO, PLAZA, ...).
"""
import json
import sys

if len(sys.argv) != 3:
    sys.exit(__doc__)

entrada, salida = sys.argv[1], sys.argv[2]
with open(entrada, encoding="utf-8") as f:
    data = json.load(f)

puntos, vistos = [], set()
for feat in data.get("features", []):
    props = feat.get("properties") or {}
    nombre = props.get("name")
    geom = feat.get("geometry") or {}
    if not nombre or nombre in vistos:
        continue
    if props.get("highway") not in ("trunk", "primary", "secondary") \
            and "avenida" not in nombre.lower():
        continue
    if geom.get("type") == "LineString":
        lon, lat = geom["coordinates"][0][:2]
    elif geom.get("type") == "MultiLineString":
        lon, lat = geom["coordinates"][0][0][:2]
    else:
        continue
    vistos.add(nombre)
    puntos.append({"id": f"p{len(puntos) + 1}", "nombre": nombre,
                   "x": round(lon, 6), "y": round(lat, 6), "tipo": "PARADA"})

with open(salida, "w", encoding="utf-8") as f:
    json.dump({"puntos": puntos}, f, ensure_ascii=False, indent=2)
print(f"Generados {len(puntos)} puntos en {salida}")
