# Research prompt 02: adversarial OSM PBF resource policy for Android

You are a security/performance researcher specializing in binary parsers, Protocol Buffers, the OSM
PBF format, Java/Kotlin memory behavior, Android heap limits, streaming algorithms, and denial-of-
service resistance. You have internet access and no local checkout. Inspect Tracker through the
immutable GitHub links below as well as upstream sources.

Produce an enforceable resource policy for Tracker's offline `.osm.pbf` importer. The result must let
an implementation agent add correct limits and tests without guessing at attack shapes or treating
input byte size as a memory bound.

## Immutable repository target and required entry points

Review exactly commit `fb2684d53ea2900780feb3438e736fbcd1b26c3f`:

- [Commit](https://github.com/adsamcik/Tracker-Android/commit/fb2684d53ea2900780feb3438e736fbcd1b26c3f)
- [Tree](https://github.com/adsamcik/Tracker-Android/tree/fb2684d53ea2900780feb3438e736fbcd1b26c3f)
- [Pinned dependency versions](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/gradle/libs.versions.toml)
- [OSM module build](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/build.gradle.kts)
- [`OsmPbfStreamingParser`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/main/java/com/adsamcik/tracker/osm/io/OsmPbfStreamingParser.kt)
- [`ParsedOsmWay`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/main/java/com/adsamcik/tracker/osm/io/ParsedOsmWay.kt)
- [`OsmGridIndex`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/main/java/com/adsamcik/tracker/osm/io/OsmGridIndex.kt)
- [`OsmImportWorker`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/main/java/com/adsamcik/tracker/osm/imp/OsmImportWorker.kt)
- [`OsmPbfStreamingParserTest`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/test/java/com/adsamcik/tracker/osm/io/OsmPbfStreamingParserTest.kt)
- [`OsmImportWorkerTest`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/test/java/com/adsamcik/tracker/osm/imp/OsmImportWorkerTest.kt)

Follow parser helpers, all allocation sites, exception types, cancellation checks, WorkManager inputs,
and persistence buffering. For every code finding, link a tight line range at this commit, trace the
input-to-allocation/failure path, assign `P0`–`P3`, and name the proving test. Compare Tracker's pinned
library usage with upstream source at the exact dependency versions.

## Evidence rules

Browsing is mandatory. Cite claims inline using direct links.

Prioritize:

1. The OSM PBF specification, official OpenStreetMap wiki where normative material is unavailable,
   and upstream `openstreetmap/pbf` Java source at version 1.6.1.
2. Protocol Buffers official encoding/security/limits documentation and source for every version
   present in the resolved configurations (the `osmpbf` POM declares 4.33.2; Tracker's catalog
   declares 4.35.1).
3. Android runtime and memory-management documentation.
4. Peer-reviewed or standards-based parser-security literature.
5. Carefully identified device measurements or upstream issue reports only as non-normative evidence.

Inspect upstream source rather than relying on artifact descriptions. State whether each important
limit is enforced by the PBF format, the upstream Java reader, protobuf-java, Tracker's described
logic, or not enforced at all. Do not quote undocumented heap-size folklore as a guarantee.

Separate external facts, commit-pinned repository facts, inferences, recommendations, and values
requiring local/device measurement.
Never fabricate a safe numeric cap. If evidence cannot determine a number, supply a conservative
derivation formula, initial bound, telemetry plan, and failure criterion.

## Verified Tracker dossier

The dossier was verified locally at the target commit, but it is orientation rather than a substitute
for review. Confirm each relevant claim from the linked code and report any discrepancy.

- Local-first Android app, minimum API 26, Kotlin/JVM 17.
- Parser dependency: `org.openstreetmap.pbf:osmpbf:1.6.1`. Its POM declares protobuf-java 4.33.2;
  Tracker's catalog declares 4.35.1, but the direct app declaration is debug-only. Treat the exact
  debug/release/test runtime selection as unresolved until configuration-specific dependency
  evidence proves it.
- The user explicitly selects a local file through Storage Access Framework; there is no network
  download. Treat the file as untrusted despite user selection.
- WorkManager runs a foreground, offline import. The import can be cancelled or killed.
- Declared compressed input-size limit: 100 MiB.
- Two-pass parser:
  1. Scan every way. Retain every driveable way in an in-memory `ArrayList`. Each retained way contains
     metadata and a primitive `LongArray` of all node references. Also retain a boxed `HashSet<Long>`
     of distinct referenced node IDs.
  2. Re-open and scan the PBF. Retain positions for referenced nodes in a boxed
     `HashMap<Long, Long>`.
  3. Resolve buffered ways into polylines and emit bounded persistence batches.
- The parser processes PBF blocks through upstream `BlockInputStream`; it does not read the entire raw
  file into one byte array.
- Distinct referenced nodes are adaptively capped from `Runtime.maxMemory()`:
  25% of max heap divided by a conservative 200 bytes per node, clamped to a 256,000 floor and
  2,000,000 ceiling.
- Current KDoc estimates roughly 70–80 bytes for boxed set/map entries. These are estimates, not
  measured guarantees.
- Missing-node ways and ways with invalid/excessive spatial coverage are rejected during resolution.
- Confirmed gap: **total retained ways and total node references across those ways are unbounded**.
  A file can repeat a small set of distinct node IDs across many driveable ways, remain below the
  distinct-node cap, and grow `wayBuffer` plus `LongArray` storage until OOM.
- Persistence batches are bounded, but batching happens only after both PBF passes; it does not bound
  the retained pass-1 graph.

## Required investigation

### A. Build the real threat and failure model

Enumerate adversarial and pathological, standards-conforming or malformed input shapes, including:

- many driveable ways sharing the same two or few node IDs;
- one or many ways with huge reference counts;
- many tags, unusually large strings/string tables, or repeated metadata;
- compressed PBF blobs with high expansion ratios;
- extreme numbers of small blobs/primitive blocks;
- malformed varints, counts, deltas, coordinates, IDs, and length prefixes;
- duplicate way IDs and nodes;
- relations or entity classes Tracker ignores but still must decode/skip;
- CPU-heavy inputs that remain inside memory caps;
- allocation churn/GC pressure below nominal retained-size limits;
- integer overflow in counters and budget arithmetic;
- cancellation or process death during each parser phase and persistence batch;
- content-provider streams whose reported file size is absent, wrong, or changes.

For each, identify the first allocating/processing layer and any verified upstream guard.

### B. Establish PBF and library limits

Research the normative/recommended limits for BlobHeader, Blob, raw and compressed sizes, PrimitiveBlock
contents, dense nodes, string tables, and Protocol Buffers message parsing. Verify exactly what
`BlockInputStream` 1.6.1 checks and whether protobuf-java size/recursion limits apply to this code path.
Identify decompression libraries and zip-bomb-equivalent considerations for zlib or other supported
encodings. Do not assume a spec recommendation is enforced by code.

### C. Derive a combined memory budget

The existing node cap allocates 25% of max heap to nodes but does not account jointly for ways,
reference arrays, block decoding, string tables, persistence batches, Room, WorkManager, or the rest
of the app.

Propose a resource-accounting model that covers at least:

- distinct referenced node IDs;
- retained node positions;
- number of retained ways;
- total node references;
- estimated bytes for way objects, strings/tags kept per way, arrays, and collection overhead;
- largest decoded block/message and decompression buffer;
- output polyline/cell batches and database-write buffers;
- temporary duplication during list/array growth and `toList`/encoding;
- CPU work and wall duration;
- optional temporary disk if spooling is recommended.

Address 32-bit versus 64-bit ART, compressed object references, alignment, collection load factors,
and why static per-object estimates cannot be universal. Recommend overflow-safe counter types and
the exact point at which each counter must be incremented and checked.

Compare three policies:

1. Reject when combined way/reference budgets are reached.
2. Spill pass-1 way records to a private temporary file or SQLite staging table, retaining only node
   IDs in memory.
3. Redesign parsing/persistence to partition work spatially or externally sort/join nodes and ways.

Score security, complexity, cancellation cleanup, disk amplification, flash writes, performance, and
ability to import realistic city/region extracts. Recommend the smallest safe V10 policy and a later
scaling path only if justified.

### D. Define user-visible and operational behavior

Specify:

- distinct error categories and safe messages for file size, decompressed block, nodes, ways,
  references, CPU/time, malformed input, insufficient memory, and insufficient temporary disk;
- whether low-memory signals can be used predictively or only diagnostically;
- progress semantics when the number of entities is unknown;
- cancellation polling granularity;
- cleanup requirements after rejection, OOM, force-stop, and reboot;
- telemetry that is useful without logging filenames, URIs, coordinates, or OSM content;
- whether retrying unchanged input can ever succeed and how to avoid a retry loop.

### E. Design validation inputs

Define a deterministic test corpus with tiny generated PBFs plus larger benchmark extracts. Include
boundary-minus-one, exact-boundary, and boundary-plus-one cases for every limit. Require tests for
high compression, repeated references, huge single ways, malformed headers, counter overflow,
cancellation, and staging cleanup. Identify trustworthy tools/libraries for generating PBF fixtures
and how to pin their version.

For real extracts, specify a measurement protocol across low-, middle-, and high-memory Android
devices or emulators. Metrics must include peak Java/native RSS where observable, GC time, allocation
rate, parse duration, temporary disk, database growth, ways, total references, distinct nodes, and
block maxima. Do not nominate a final cap merely by observing one city.

## Required output

Return one Markdown report in this order:

1. **Security verdict** — whether the current 100 MiB + distinct-node limits are sufficient.
2. **Format/library enforcement map** — limit, normative source, upstream enforcement, residual risk.
3. **Threat matrix** — input shape, affected resource, current guard, exploitability, consequence.
4. **Combined resource model** — counters, formulas, overflow handling, and uncertainty margins.
5. **V10 policy recommendation** — exact policy structure and why it is the smallest safe choice.
6. **Scaling alternative** — reject versus disk staging versus deeper redesign.
7. **Failure and UX contract** — error taxonomy, retry, progress, cancellation, cleanup, privacy.
8. **Deterministic adversarial test corpus** — fixture specification and expected outcome.
9. **Real-device benchmark protocol** — device classes, measurements, and decision thresholds.
10. **Implementation checklist** — language-agnostic placements for checks and cleanup.
11. **Unknowns requiring upstream experiment or local verification.**
12. **Evidence ledger** — authority, version/date, claim, limitations.

End with **Unsafe shortcuts**, explicitly rejecting file-size-only safety, independent caps whose
budgets add beyond the heap envelope, catching OOM as routine control flow, and unmeasured universal
object-size constants.
