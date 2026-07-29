# Research prompt 03: antimeridian-safe geometry and imported-coordinate contract

You are a geodesy and geospatial-software researcher specializing in longitude normalization,
antimeridian geometry, local tangent-plane approximations, integer coordinate encodings, spatial
indexes, and property-based numerical testing. You have internet access and no local checkout. Inspect
Tracker through the immutable GitHub links below.

Tracker has several independently written geospatial paths. Produce a compact, reusable mathematical
contract for short-range road snapping and imported coordinates so implementation agents stop fixing
the same longitude-overflow class one file at a time.

## Immutable repository target and required entry points

Review exactly commit `fb2684d53ea2900780feb3438e736fbcd1b26c3f`:

- [Commit](https://github.com/adsamcik/Tracker-Android/commit/fb2684d53ea2900780feb3438e736fbcd1b26c3f)
- [Tree](https://github.com/adsamcik/Tracker-Android/tree/fb2684d53ea2900780feb3438e736fbcd1b26c3f)
- [`OsmSpeedLimitSource`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/main/java/com/adsamcik/tracker/osm/speed/OsmSpeedLimitSource.kt) and [tests](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/test/java/com/adsamcik/tracker/osm/speed/OsmSpeedLimitSourceTest.kt)
- [`OsmGeometry`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/main/java/com/adsamcik/tracker/osm/match/OsmGeometry.kt) and [tests](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/test/java/com/adsamcik/tracker/osm/match/OsmGeometryTest.kt)
- [`OsmGridIndex`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/main/java/com/adsamcik/tracker/osm/io/OsmGridIndex.kt) and [tests](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/test/java/com/adsamcik/tracker/osm/io/OsmGridIndexTest.kt)
- [`OsmPbfStreamingParser`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/main/java/com/adsamcik/tracker/osm/io/OsmPbfStreamingParser.kt)
- [`OsmStreetResolver`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/geocoder/src/main/java/com/adsamcik/tracker/geocoder/osm/OsmStreetResolver.kt) and [tests](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/geocoder/src/test/java/com/adsamcik/tracker/geocoder/osm/OsmStreetResolverTest.kt)
- [`JsonImport`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/file/JsonImport.kt) and [tests](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/file/JsonImportTest.kt)
- [Real-time session distance code](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/stats/engine/src/commonMain/kotlin/com/adsamcik/tracker/stats/engine/segment/SessionSegmentDetector.kt)

Trace helpers and call sites rather than reviewing each file in isolation. Identify duplicate longitude
algebras that should converge. Code findings must link tight line ranges, show the concrete overflow or
geographic invariant violation, assign `P0`–`P3`, and name a regression/property test.

## Research discipline

Browse and cite primary sources inline. Prefer recognized geodesy references and implementations
(for example authoritative GeographicLib documentation/source and relevant OGC/ISO material), the OSM
data model for longitude conventions, official Android location documentation where applicable, and
peer-reviewed numerical/geodesic literature. Clearly distinguish a standard requirement from a
library convention.

Do not paste a generic haversine function. Prove or bound the approximation recommended for Tracker's
distance scale. Every formula must define units, valid input domain, wrap convention, pole behavior,
overflow behavior, and expected output range. State how signed zero and the equivalent +180°/-180°
representations are canonicalized.

Label external facts, commit-pinned repository facts, inferences, recommendations, and open local/
device validation needs. Do not claim to have run Tracker tests.

## Verified Tracker dossier

The dossier was verified locally at the target commit, but it is orientation rather than a substitute
for review. Confirm each relevant claim from the linked code and report any discrepancy.

- Coordinates are commonly persisted as signed E7 integers: degrees multiplied by 10,000,000.
  Valid latitude is `[-90°, +90°]`; valid longitude is `[-180°, +180°]` at input boundaries.
- An E7 coordinate fits in signed 32-bit `Int`, but subtracting two valid longitudes in `Int` can
  overflow because the mathematical delta can approach 3.6 billion E7 units.
- Multiple recent defects arose from direct E7 longitude subtraction near ±180°.
- The road matcher now has at least one antimeridian-safe geometry helper, but a sibling speed-limit
  lookup still performs local planar geometry with raw expressions equivalent to
  `(bLonE7 - aLonE7).toDouble()` and `(pLonE7 - aLonE7).toDouble()`.
- The speed lookup searches for the nearest road within 50 metres. It computes equirectangular local
  x/y metres using cosine(latitude), projects the point onto short polyline segments, and filters by
  stored E7 bounding boxes plus a fixed E7 margin.
- Imported OSM polylines may cross the antimeridian. Some bounding-box code represents a narrow
  antimeridian crossing with `minLon > maxLon`; other ordinary boxes use `minLon <= maxLon`.
- A coarse global grid index must split or otherwise represent antimeridian-crossing boxes without
  allocating cells across almost the whole world.
- JSON location imports already accept an observation but clear both coordinates when the latitude/
  longitude pair is non-finite or out of range.
- JSON Wi-Fi and cell imports currently check coordinate finiteness independently but do not check
  range. A very large finite value can be converted/rounded into an invalid E7 integer. One coordinate
  can be retained without the other.
- The desired bounded correction is to align Wi-Fi/cell coordinate behavior with the existing
  location-import pair contract; it is not a request to reject an otherwise useful observation.

## Questions to answer

### A. Define the longitude algebra

Specify a canonical function for:

- normalizing an arbitrary longitude in degrees and E7;
- computing the signed shortest longitude delta from A to B;
- resolving the exact 180° tie deterministically;
- adding a delta and renormalizing;
- interpolating a segment across the antimeridian;
- calculating circular longitude interval membership and expansion by a margin;
- calculating the minimal circular bounding interval for a polyline;
- splitting a crossing interval for a conventional SQL/grid query.

All integer intermediates must be overflow-safe. Compare floor-modulo, remainder, branch-based, and
floating implementations. Account for inputs at both -180° and +180°, values multiple worlds outside
the canonical range, and corrupted persisted values. State whether Tracker should canonicalize stored
+180° to -180° or preserve both at boundaries while using one internal representation.

### B. Select the 50-metre projection model

Compare equirectangular/local tangent-plane projection, spherical great-circle cross-track distance,
and ellipsoidal geodesic projection for point-to-short-polyline distance. Determine:

- the error bound or empirical validation required for a 50 m snap threshold;
- behavior at high latitude as `cos(latitude)` approaches zero;
- whether longitude scaling should use query latitude, segment midpoint, or another latitude;
- when a segment is too long or too close to a pole for the local model;
- how degenerate and repeated vertices are handled;
- how to project/interpolate a dateline-crossing segment without taking the long path;
- whether distance comparison and returned projection point need different accuracy levels.

Recommend a tiered approach if a fast local method is safe for ordinary segments but a robust fallback
is needed for polar/long/pathological geometry. Define the switch condition without arbitrary magic.

### C. Establish bounding-box and grid invariants

Create explicit invariants for ordinary and crossing longitude intervals. Answer:

- How should a 50 m geographic search margin convert to longitude degrees as latitude changes?
- What happens when the margin reaches or exceeds a half/full world near a pole?
- Can E7 bounds be safely expanded without exceeding valid Earth coordinates or integer bounds?
- How should grid cell enumeration prove a strict maximum allocation?
- Should persistent bboxes use crossing `min > max`, split rows, center/span, or unwrapped values?

Compare representations for query simplicity, Room/SQLite indexing, backward migration, and misuse
resistance. Recommend one canonical domain contract even if current persistence cannot change yet.

### D. Define the import-coordinate contract

For optional `(latitude, longitude)` pairs on Wi-Fi/cell observations, decide behavior for:

- both absent;
- one absent;
- NaN, positive/negative infinity, signed zero;
- latitude just inside/on/outside ±90°;
- longitude just inside/on/outside ±180°;
- finite values whose E7 conversion would overflow or saturate;
- excessive decimal precision;
- a valid coordinate paired with provenance claiming an incompatible source.

The product preference is to preserve the non-location observation but store no coordinate when the
pair is unusable. Determine whether coordinate provenance must also become `UNKNOWN` when coordinates
are cleared. Define round-to-nearest behavior, tie semantics if material, and preconditions that make
E7 conversion safe before any `Int` conversion.

### E. Produce executable test vectors and properties

Supply numeric test vectors for Prague/ordinary latitude, high latitude, near both poles, both sides
of the antimeridian, exact ±180°, a segment whose endpoints straddle the dateline, and invalid inputs.
Give expected qualitative and, where defensible, numerical results with tolerances and source or
derivation.

Define property-based tests such as normalization idempotence, antisymmetry with a documented 180°
exception, bounded shortest delta, translation/wrap invariance, projection invariance under ±360°,
finite distances, symmetry, and bounded grid enumeration. Include integer boundary values and
mutation tests that would catch subtraction-before-widening.

## Required output

Return one self-contained Markdown report:

1. **Recommended canonical contract** — a short normative summary.
2. **Source-backed geodesy foundations** — conventions and applicability.
3. **Longitude functions** — formulas/pseudocode, domains, and overflow proof.
4. **Point-to-polyline model decision** — accuracy, fallback, and pole behavior.
5. **Bounding-box/grid contract** — representations and bounded enumeration.
6. **Optional import-coordinate contract** — decision table including provenance.
7. **Shared API design** — language-neutral types/functions that discourage unsafe raw subtraction.
8. **Deterministic test vectors** — inputs, expected outputs/tolerances, derivation.
9. **Property and fuzz test plan.**
10. **Migration/compatibility cautions** — persisted +180°, crossing bboxes, legacy invalid E7.
11. **Unknowns requiring local validation.**
12. **Evidence ledger** — source authority, version/date, supported claims, limits.

End with **Banned implementation patterns**, including subtracting E7 `Int`s before widening, treating
longitude as a linear interval globally, fixed longitude-degree padding at all latitudes, and relying
on `roundToInt` saturation as validation.
