# Optional map enrichment continuation

Date: 2026-09-27. **IMPLEMENTED_UNVALIDATED; whole request incomplete.**

Original user request:

> **Finish optional map enrichment.** Connect qualified Location context to Wi-Fi/Cell displays, preserve uncertainty and gaps, and invalidate results when either source changes.

## Phase and workspaces

IMPLEMENTATION_ONLY remains in force. Production/test-source authorship, static inspection/review
and coherent local candidate checkpoints only. No Gradle, compilation, executed tests, lint,
Detekt, schema generation/drift, git diff --check, emulator/device/UI evaluator, battery, CI,
release/rollout validation, provider/writer activation, input freeze, new dev/v10 integration or
push. Main dev/v10 remains 13f1a99871; the candidate starts at 616619e32b. Matcher/observer checkpoint: d685b73664.
Protected root paths, original refs and excluded drafts are preserved.

## Explicit decisions

The user approved the proposed join rules through the question tool: Wi-Fi exact or two qualified
bracketing fixes within30s, Cell preceding fix within60s, and no added100m accuracy cutoff. Preserve
finite nonnegative reported horizontal accuracy, both wall timestamps and uncertainties. Match
elapsed intervals conservatively; actual Location endpoints only, no interpolation/AP/tower estimate.
Both inputs must independently qualify for the same purpose and compatible ownership/clock/epoch.
Missing Location context leaves primary radio history intact.

The user separately approved one source-owned read-only stats/api enrichment API implemented in
stats/data: authenticate retained Location receipts and tagged grants, qualify native radio facts,
and read paired sets in one bounded Room snapshot with explicit gaps/limit outcomes. No acquisition.

Still awaiting the earlier concrete display-placement question: native observed device points with
accuracy rings in existing signal layers versus separate observed-source layers. Native facts have
no BSSID/tower identity; legacy identity counts and inferred network positions cannot be fabricated
from aggregate native data. Historical Location collection-off/permission semantics remain separately
pending from the retained-authority/history continuations. No answer is inferred from elapsed time.

## Authored and independently reviewed slice

- RadioLocationContextResolver uses exact capture logical/run/segment/boot/clock/epoch equality;
  ambient scope is boot/clock/epoch equality after each input's independent authorization. Consent
  numbers across different sources need not equal. Caller-provided eligibility is not provenance.
- Wi-Fi brackets the entire radio observed interval, checks both endpoints' conservative maximum
  separation, chooses nearest observed endpoint and earlier on ties. Cell accepts only preceding
  endpoints relative to interval start, within60s of interval end. Equal-time event identities are
  stable, ordered arithmetic cannot overflow, and reported separation is rounded up to milliseconds.
- Coarse finite accuracy remains available; invalid accuracy is omitted rather than repaired.
  Both wall timestamps and both uncertainty values survive the match.
- MapDataChangeObserver now registers native capture/ambient facts, revisions/cursors, Location
  receipt/purpose/retention rows and shared policy/consent/epoch/deletion/provider/manifest/lane
  dependencies. Source-local mutations retain their narrow source; shared authority changes
  requalify all three. The production initial-registration handshake and committed Room invalidation
  flow are preserved, with an internal existing-database constructor for the Room regression source.
- Resolver boundary/scope/uncertainty/retraction source cases, observer dependency classification
  source cases and a real Room committed-epoch/registration source case are authored, unexecuted.

This slice does not connect native map rendering. MapStore still maps radio layers only to their
own source, so a Location-only commit does not yet reload those radio layers. The resolver has
no production caller and expects already-qualified inputs. Observer registration is not end-to-end
both-input cache invalidation proof.

## Exact next implementation seams

Existing GeoRepositoryImpl, RadioQueryBuilder and Wifi/CellRadioAggregator use legacy identities
and legacy geographic estimates. Do not feed native aggregate counts into those identity products.
The paired native reader must resolve references to actual effective cursor-selected retained radio
facts in the same transaction; a public localSourceEventId alone is not authority. Imported records
have no local native join authority. Apply read budgets before unbounded expansion, report limits
and missing context explicitly, and never query once per point.

No complete retained Location facade exists: LocationReadScope/visiblePathChunk are visibility
filters, RoomCanonicalEvidenceReader is raw replay, and the engine's LocationWalQualificationAdapter
is acquisition qualification that cannot read pruned retained products. Reusable checks include
protectedLocationCanonicalReceiptBytes plus canonical digest, tagged carrier fields, historical
manifest integrity, retention approval integrity and provider interval authentication. Purpose-row
presence alone is not an adequate replacement. Do not reconstruct grants from current OS permission,
require fresh WAL for retained history, or invent missing source-local erase settlement.

Confirmed by root and independent source review: RoomAmbientWifiRepository/Cell local effective
queries and toApiFact conversions provide current-cursor/checksum mapping without complete per-fact
retained consent/epoch/retention/deletion and aggregate-owner/lineage authentication. Returned local
facts are not qualified enrichment inputs. This remains a shared source-owned reader obligation;
latest authority merely changing the availability label does not retract unauthorized facts.

After the reader exists, wire the approved display choice, both-input layer/cache dependency sets,
uncertainty and absent/limited context presentation, then author source-owned Room and MapStore/UI
regression sources. Full convergence and physical-device evidence remain deferred.

## Shared contract and canonical content continuation

The approved stats/api RadioLocationContextRepository contract is authored: a bounded native
Wi-Fi or Cell range, independent capture/ambient tags, original source-specific signal quality,
optional actual Location endpoints with distinct timestamps/uncertainties, explicit missing-context
reasons, purpose-scoped source/dependency gaps and whole-request dependency/storage outcomes.
A dual-purpose event represents two independently qualified associations, not two physical scans;
consumers must deduplicate physical-event presentation/counting without lending purpose authority. The maximum observation bound reuses the
existing native radio 4096-row cap. Imports gain no native context authority. This interface has no
production implementation or DI binding yet and is not a usable reader.

LocationAcceptedReceiptIntegrity centralizes the exact preexisting protected canonical sample
SHA-256 encoding; both capture and ambient writers reuse it without changing checksum bytes or
stored receipt fields. matchesCanonicalSample checks exact canonical sample identity/time/clock
and content only. It deliberately does not claim to authenticate registration, sequence or purpose
authority; the focused source case demonstrates that distinction. API and content regression
sources are authored, unexecuted.

### Confirmed durable Location proof gap and required storage decision

Root and independent adversarial source review found that canonical receipt V4 and raw canonical
observations/samples do not retain the original registration/sequence/configuration/authorization
and tagged purpose tuple. Continuity anchors retain only a latest point. Registration plans bind
a valid physical tuple but not this event; event IDs are random. SourceEventRetentionProof stores
an opaque original authority fingerprint and a subset of event/scope fields, so the original full
authority fingerprint cannot be recomputed from the current Location carrier after WAL pruning.
Canonical content plus a plausible valid registration/grant is not independent exact-tuple proof.

The question tool asks approval for versioned integrity proof over the complete accepted receipt
and each independent purpose grant, committed atomically by the existing qualified writer. Older
records require surviving original evidence or remain an honest context gap; reads do not repair
or manufacture proofs. Alternative: rely on surviving original raw events and accept older
context gaps. No answer received; no new proof table/column/codec, migration or proof writer has
been implemented. This is a new storage/trust contract requiring confirmation under AGENTS.md.
The shared reader/API approval alone is not approval to invent this missing proof contract.

A strict original-WAL-only reader could be a limited fallback but would not satisfy the planned
durable enrichment; do not label it complete or quietly depend on fresh WAL. Qualifying capture
must also validate historical exact membership, provider/authorization intervals and source-local
fences. Ambient radio facade repair requires complete current cursor/revision/owner lineage and
independent historical/current consent/retention/deletion/replay qualification, preserving siblings.
No authenticated ambient prefix anchor exists: missing revision1, holes, mismatched latest cursor
or bounded-read overflow remain explicit unavailable outcomes.

Additional broader gap: ambient portable export reads the facade in one transaction, then fetches
lineages outside it. Qualification repair alone will not close that correction/erase race. Preserve
existing selected-format semantics; do not use export conversion as the paired native read seam.
