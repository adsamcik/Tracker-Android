# Research evidence schema v1

`stats:api` owns the common, typed V10 research-evidence contract. Schema version `1` is a
deterministic in-memory/replay boundary only: it does not authorize local capture, a plaintext file
or database, upload, analytics, or a collection UI.

Every `ResearchEvidenceEnvelope` carries a trace/run/session identity, a trace-scoped monotonic
sequence, declared clock domain, privacy class, algorithm versions, capability declaration,
lifecycle boundary, loss ranges, and one typed record. A final envelope may additionally carry a
`ResearchTerminalIntegrityRecord`. Its envelope count, first/last sequence, loss-range count and
saturated lost-event count make accounting explicit; an absent terminal record never means that an
historical export was complete.

Loss ranges are inclusive trace-sequence intervals. They must be disjoint, and a recorder requires
every skipped envelope sequence to be covered by contiguous declared loss. Raw pressure source
sequences are deliberately separate from aggregate and location source-sequence namespaces.

The schema includes typed pressure descriptor/raw-event/aggregate records, altitude
conversion/calibration/filter records, lifecycle/gap/loss/truth/manual-correction records, a
lossless canonical segmentation observation, and versioned reducer output. Canonical location,
step, activity, decision, lifecycle, and capability fields preserve absence rather than turning it
into negative evidence. The named `LegacyV1SegmentationProjection` is the sole intentionally lossy
adapter.

`ResearchEvidenceCodec` maps every domain record to its corresponding
`ResearchEvidenceWireRecord` and back. It sorts algorithm-version keys when encoding and rejects
unknown schema versions. The common test suite verifies domain/wire round trips, declared
capabilities, loss accounting, terminal counts, and this document path constant. Any future
encrypted transport must retain that mapping and add a versioned serialization format; it must not
silently reinterpret absent capabilities or terminal integrity.

The debug authenticated research-trace exporter remains a separate historical v3 manifest. It
declares the capabilities it actually has and marks ordinary historical exports incomplete; it is
not a writer for this schema-v1 contract.
