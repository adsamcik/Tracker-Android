# Research prompt 04: OSM matcher and vehicle-compliance validation

You are a principal researcher in map matching, GNSS uncertainty, road-network routing, OSM tagging,
trajectory evaluation, and reproducible geospatial testing. You have internet access and no local
checkout. Inspect Tracker through the immutable GitHub links below.

Prepare an evidence and experiment plan that tells Tracker which matcher improvements are worthwhile,
in what order, and what end-to-end tests must exist first. Focus especially on one-way direction,
turn restrictions, candidate/transition modeling, and speed-compliance classification. Do not respond
with a survey of map-matching papers detached from the supplied product.

## Immutable repository target and required entry points

Review exactly commit `fb2684d53ea2900780feb3438e736fbcd1b26c3f`:

- [Commit](https://github.com/adsamcik/Tracker-Android/commit/fb2684d53ea2900780feb3438e736fbcd1b26c3f)
- [Tree](https://github.com/adsamcik/Tracker-Android/tree/fb2684d53ea2900780feb3438e736fbcd1b26c3f)
- [`OsmPbfStreamingParser`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/main/java/com/adsamcik/tracker/osm/io/OsmPbfStreamingParser.kt)
- [`OsmHmmMapMatcher`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/main/java/com/adsamcik/tracker/osm/match/OsmHmmMapMatcher.kt), [`OsmGeometry`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/main/java/com/adsamcik/tracker/osm/match/OsmGeometry.kt), and [matcher tests](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/test/java/com/adsamcik/tracker/osm/match/OsmHmmMapMatcherTest.kt)
- [`OsmRoadClass`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/main/java/com/adsamcik/tracker/osm/OsmRoadClass.kt) and [`OsmMaxspeedParser`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/main/java/com/adsamcik/tracker/osm/io/OsmMaxspeedParser.kt)
- [`OsmSpeedLimitSource`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/main/java/com/adsamcik/tracker/osm/speed/OsmSpeedLimitSource.kt)
- [`DefaultRoadMatcher`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/stats/data/src/main/java/com/adsamcik/tracker/stats/data/roadmatch/DefaultRoadMatcher.kt) and [`DefaultSpeedLimitSource`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/stats/data/src/main/java/com/adsamcik/tracker/stats/data/speed/DefaultSpeedLimitSource.kt)
- [Map layer registry/wiring](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/feature/map/src/main/java/com/adsamcik/tracker/map/layers/registry/DefaultLayerRegistry.kt)
- [`VehicleComplianceLayer`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/feature/map/src/main/java/com/adsamcik/tracker/map/layers/impl/VehicleComplianceLayer.kt) and its current [`VehicleComplianceE2ETest`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/feature/map/src/test/java/com/adsamcik/tracker/map/layers/impl/VehicleComplianceE2ETest.kt)
- [`OsmDispatcherIntegrationTest`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/stats/data/src/test/java/com/adsamcik/tracker/stats/data/speed/OsmDispatcherIntegrationTest.kt)

Follow the full path from imported entities through candidates, HMM/Viterbi transitions, speed-source
selection, layer provider, classification, and rendering model. Code findings must link tight line
ranges, show reachability, assign `P0`–`P3`, and identify the test/trace that would prove a correction.

## Evidence standard

Browse extensively. Cite claims inline.

Prioritize:

1. Seminal and current peer-reviewed map-matching literature, including the original source for any
   HMM/Viterbi model or probability formula you recommend.
2. Official OSM data-model/tagging documentation and wiki pages, explicitly noting when the wiki is
   community guidance rather than a normative specification.
3. Primary documentation/source for proposed PBF fixture and routing tools.
4. Public dataset papers and dataset licenses.
5. Official Android location uncertainty documentation where platform semantics matter.

For each paper, report dataset, geography, sampling cadence, noise, transport mode, metrics, and limits
on transferability. Do not copy threshold values without showing why they apply to Tracker. Separate
external facts, commit-pinned repository facts, inference, recommendation, and hypotheses requiring
local/field validation.

## Verified Tracker dossier

The dossier was verified locally at the target commit, but it is orientation rather than a substitute
for review. Confirm each relevant claim from the linked code and report any discrepancy.

- Local-first Android app; OSM graphs are imported from user-selected local PBF files. No online route
  service may be assumed.
- Each retained driveable OSM way currently stores road class, resolved speed limit, whether that speed
  was explicit, a one-way boolean, packed E7 polyline, and bounding box.
- A coarse 0.01° spatial cell index retrieves candidate ways.
- The map matcher is an offline HMM/Viterbi-style implementation over candidate road polylines.
- A recent correction makes degenerate all-zero/all-non-finite candidate probabilities return
  explicit no-path rather than an arbitrary default.
- GPS horizontal accuracy is now passed into vehicle compliance instead of a universal constant.
- The importer rejects an entire way if any referenced node is missing.
- Antimeridian overflow in core geometry has been corrected, though a sibling speed lookup still has
  a bounded bug covered by another research pack.
- The parser records `oneway` as a boolean. It does not preserve reverse (`oneway=-1`) direction as a
  distinct orientation, and the matcher does not currently score travel direction.
- Turn-restriction relations are not ingested.
- Speed-limit lookup snaps a point to the nearest driveable way within 50 m and returns explicit or
  road-class-default speed.
- Vehicle compliance compares recorded speed with matched road limits and renders categories such as
  under, near limit, slightly over, and speeding. It is a user visualization, not law-enforcement or
  safety-critical control logic, but confidently wrong output is still harmful.
- Current unit tests separately cover matcher math and speed lookup.
- A test named vehicle-compliance end-to-end uses real Room location/session rows and the real layer,
  but **fakes the RoadMatcher** and locally mirrors a production provider lambda.
- A separate speed integration test uses real Room and production speed lookup but seeds OSM tables
  directly; it intentionally does not exercise PBF import.
- There is no checked-in `.osm.pbf` fixture and no full path test from PBF bytes through importer,
  database publication, real matcher, speed lookup, and compliance layer.

## Research questions

### A. Define what correctness means

Create separate goals and metrics for:

- candidate recall;
- correct road/way and direction;
- route/topology consistency;
- matched-position error;
- no-match/abstention quality;
- speed-limit attribution;
- final compliance-bucket accuracy;
- runtime, memory, and database-query cost on Android.

Explain why point accuracy, route accuracy, and compliance accuracy can disagree. Define confidence or
abstention behavior so uncertain matching does not become a confident compliance label. Recommend
metrics beyond raw percent correct, including class imbalance and transition-boundary handling.

### B. Design a genuine end-to-end fixture

Research trustworthy, version-pinnable ways to create tiny deterministic OSM PBF fixtures. Compare
checking in binary fixtures, generating OSM XML then converting it, programmatic PBF construction,
and using a small real extract. Address reproducibility, licensing/attribution, tool availability in
CI, byte stability, malformed-input generation, and fixture readability.

The minimal synthetic graph should exercise:

- overlapping imported regions and publication state;
- bidirectional, forward one-way, and reverse one-way roads;
- a legal and illegal turn at an intersection;
- parallel roads, overpass/underpass without connectivity, and a service road;
- explicit, implicit/default, unit-bearing, directional, and missing speed tags as supported;
- an antimeridian-crossing segment;
- a way with a missing referenced node;
- disconnected candidates and an intentional no-path trace;
- points with varying accuracy, gaps, low speed, and ambiguous headings.

Specify the oracle for each case. The fixture must test production parsing and publication, not merely
DAO contracts.

### C. Evaluate one-way direction support

Research robust ways to score or forbid wrong-way candidates using ordered polyline orientation,
observation displacement/bearing, speed, horizontal uncertainty, sample gaps, and transition paths.
Address:

- preserving `oneway=-1` orientation from OSM;
- roundabouts and implicit one-way conventions;
- very low speed or stationary fixes where heading is meaningless;
- noisy bearings and sparse samples;
- legitimate contraflow exceptions and mode-specific access (Tracker's first target is motor-vehicle
  compliance, but the graph may serve other modes later);
- U-turns, map edits, and imperfect OSM data;
- whether direction should be a hard constraint, a probabilistic penalty, or confidence-dependent.

Propose a staged V10/V10.x design and falsifiable acceptance thresholds, not borrowed constants.

### D. Evaluate turn-restriction ingestion

Describe the OSM restriction relation model relevant to motor vehicles: members/roles, `restriction`,
`restriction:*`, `except`, conditional restrictions, via-node versus via-way restrictions, and common
data-quality limitations. Verify current conventions from official/community primary OSM sources.

Estimate the algorithmic and storage consequences for an HMM transition model. Compare:

1. ignoring restrictions;
2. supporting only simple via-node `no_*` and `only_*` restrictions;
3. full via-way, conditional, and mode-aware support.

State what route evidence is necessary to show that each increment improves Tracker enough to justify
parser/schema complexity. Treat turn restrictions as a separate decision from one-way direction.

### E. Validate speed-limit and compliance semantics

Research OSM `maxspeed` complexities relevant to a local offline app: units, country/zone defaults,
directional limits, conditional limits, lanes, advisory speeds, variable/electronic signs, missing or
stale data, and legal context. Distinguish what a first-release resolved integer limit can honestly
represent.

Recommend:

- when to use an explicit OSM limit, a road-class default, configured fallback, or unknown;
- how uncertainty in match and limit source should propagate to visualization;
- how to avoid boundary points inheriting the wrong parallel road's limit;
- how long gaps and sparse samples should be classified;
- whether compliance buckets should be suppressed below a confidence threshold;
- user-facing caveats that are accurate without making the feature useless.

Do not give legal advice. Identify jurisdictional/default-table risks that require product decisions.

### F. Build the field-validation protocol

Identify public map-matching datasets that may help, with licenses and transferability limits. Then
design a privacy-safe Tracker-specific collection protocol using ground-truth route annotations.
Include urban canyon, parallel roads, motorway ramps, roundabouts, tunnels, rural roads, high latitude,
antimeridian synthetic tests, walking/cycling contamination, different cadences, and varying GNSS
accuracy.

Define train/tune/test separation even for a rule-based model. Prevent tuning thresholds directly on
the final acceptance routes. Specify error review artifacts that do not expose precise personal
location unnecessarily.

## Required output

Return one Markdown report:

1. **Feature-order verdict** — tests first, one-way, restrictions, and compliance changes in order.
2. **Evidence review** — applicable algorithms/papers with transferability limits.
3. **Correctness and abstention contract** — metrics and confidence propagation.
4. **Deterministic PBF E2E fixture plan** — tool choice, topology, tags, oracle, CI behavior.
5. **One-way design options** — scoring/constraints, reverse direction, edge cases, tests.
6. **Turn-restriction decision** — minimum viable subset, costs, evidence gate, defer criteria.
7. **Speed-limit/compliance semantics** — source confidence, fallback, suppression, copy.
8. **Public and Tracker-specific dataset plan** — licenses, scenarios, annotation, privacy.
9. **Benchmark and acceptance matrix** — quality and Android resource measures.
10. **Failure-analysis taxonomy** — candidate, geometry, topology, timing, OSM data, speed source.
11. **Questions requiring local implementation inspection.**
12. **Evidence ledger** — primary source, version/date, claim, applicability limits.

End with two explicit lists: **Safe before field data** and **Must wait for representative traces**.
