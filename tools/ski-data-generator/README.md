# Ski Infrastructure Data Generator

Generates `ski_infrastructure.db` from OpenSkiMap GeoJSON data. The database contains
ski lift geometry and metadata for offline spatial queries.

## Usage

1. Download `lifts.geojson` from <https://openskimap.org> (About → Download)
2. Place in `tools/ski-data-generator/input/lifts.geojson`
3. From the repository root, run: `./gradlew :tools:ski-data-generator:run`
4. Output: `tools/ski-data-generator/output/ski_infrastructure.db`

### Custom input path

```shell
./gradlew :tools:ski-data-generator:run --args="/path/to/lifts.geojson"
```

## Database Schema

- **`ski_lift`** — One row per lift with start/end coordinates, elevation, bounding box, and type
- **`ski_lift_point`** — Start, end, and 2-3 midpoints per lift for corridor matching
- **`metadata`** — Generation metadata (source URL, timestamp, lift count, schema version)

## Lift Types

Types are normalized from OpenSkiMap values:

| DB value | Source values |
|--------------|--------------------------------------|
| `chairlift` | chair_lift, chairlift |
| `gondola` | gondola |
| `cable_car` | cable_car |
| `drag_lift` | drag_lift, t-bar, j-bar, platter, rope_tow |
| `magic_carpet`| magic_carpet |
| `funicular` | funicular |
| `mixed_lift` | mixed_lift |

## Filtering

Only lifts with `status == "operating"` or no status are included.
Abandoned, closed, demolished, disused, and razed lifts are excluded.
