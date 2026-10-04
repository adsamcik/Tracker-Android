# Independent source review — all-day shared status V2

## Decision and phase

**REPAIR REQUIRED: AD1.** The selected read-only seam and actual History reuse are otherwise consistent with the approved scope. AD2 is a fixture coverage correction. This is static source review, **IMPLEMENTED_UNVALIDATED**. No compilation, tests, schema/drift checks, diff-check, CI, devices, provider activation or Git mutations were performed. All 25 full-chain prerequisites remain open.

Reviewer: independent review-only agent `fresh_radio_schema_review_1004`. Original task messages, follow-ups and approvals were inherited. Latest priority: complete the tracking architecture/system, including all-day Steps independent of sessions, using the approved small read-only stats API and existing source readers.

## Exact input

- Packet: `ALL_DAY_SHARED_STATUS_V2_SELECTED`.
- Base: `32c8cd8fe60bf77457b83dbc84694188e485e3d6`; extra fixture base `1056464a1376e3574f3a744a6eaf5e3402accaf7`.
- `PACKET_PINS.json`: `823f2e963b6b0fcef2ec9fdc6a988f7fa57e1498bc1dd5a136ad1e68da50e084`.
- `FILES.json`: `093e55a0e1c01da9eb6bf636cc91924dc12fbe6d1e3313dd2a7dd9dfa5a390ed`.
- `SELECTION.patch`: `f4fcaf3d3fa50ee7f7437b58b0081adb47039881db9b7e6cf8ff74a05ea5c44f`.
- `DEPENDENCY_INPUTS.json`: `397dc720d5aa9e53da47c8face8087fed4f881a16a64418d73d142cdf37b0b5b`.
- `SOURCE_BOUNDARY.md`: `29bfd3b3087a5cb7f36d3702503487cdaca3c19bc1ce9872add281ff80f282c4`.

All five metadata pins, 46 applicable BASE/WORKING_BEFORE/WORKING_AFTER/SELECTED artifact hashes for 13 selected paths, and all 57 frozen dependency hashes match. Review used immutable selected/dependency bytes, not moving working files. The first selection and excluded working content remain preserved.

## AD1 — malformed retained authority can produce qualified all-day status

`SELECTED/stats/data/.../RoomAllDaySourceStatusQuery.kt:60–62` accepts any existing `SourceEvidenceState` and constructs a snapshot before calling source readers. The snapshot checks only nonnegative epoch/revision. The entity has no constructor shape check. In contrast, `SELECTED/.../UnifiedHistoryRangeReader.kt:101,775–777` rejects negative retention floor, deletion high-water ordinal or updated time as `INTEGRITY_UNAVAILABLE`.

This is a concrete classification discrepancy: a valid retained native Location fixture plus `retained_from_ms = -1` or `deleted_source_event_high_water_ordinal = -1` still passes the reused Location qualifier's lower-bound comparisons and can yield a Location point in all-day status. The same authority row cannot produce a History page. Radio qualifiers likewise consume these fields directly; no delegated shape guard closes this path. This permits a positive native-status claim from malformed dataset authority.

Smallest repair: reuse the existing same-module evidence shape predicate before any source-specific read; make the existing function internally visible if necessary. Return the existing `Unavailable(INTEGRITY_UNAVAILABLE)`. No new storage, identity, proof stage or API type is needed. Author an actual Room case with a valid native baseline, a malformed authority field, an unavailable result and unchanged saved data. Keep existing valid, Off, epoch and retention behavior.

## AD2 — imported-only fixture is not actually imported

`SELECTED/.../RetainedLocationContextReaderTest.kt:45–57` calls its new case “excludes unknown and imported points without hiding their history”, but the second point only has foreign event/signal IDs. It has no `location_file_identity` or archive membership, so the frozen `LocationReadScope.HISTORY_ORIGIN` classifies it LEGACY. The History assertion checks only the unsealed native receipt.

The production exclusion is plausible from the receipt-only discovery and separate imported-copy case, but this case does not establish the named imported-only behavior. Either accurately narrow the title/coverage claim, or use existing identity/archive fixture rows for a true imported-only point and show it remains in imported History while absent from native status. This is test-source accuracy, not a request for new import behavior.

## Committed dependency closure

Root's later `HEAD_DEPENDENCY_COMPARISON.json` identifies four frozen inputs differing from committed head `1056464a1376e3574f3a744a6eaf5e3402accaf7`. I independently compared their exact contents. None of those draft deltas is required by the selected all-day path:

- `DefaultAmbientStepsHistoryRepository`: the façade calls the existing `readBackgroundInCurrentTransaction`, which passes `includeSessions=false`. Its native/import range branches and count composition are unchanged. The held recent pagination and session-reader rewrite are not entered by this seam.
- `AmbientStepsFactRevisionDao`: only held recent-cursor SQL differs. Native status uses existing latest-effective overlap, revisions and importer authority reads.
- `LocationPurposeDao`: only an added `latestRegistrationPlan` differs. Status uses the existing bounded `contextReceiptsOverWindow` and context-purpose reader.
- `RadioObservationChildDao`: differences add/change export-owner helpers. Selected status/History radio reads use existing background child readers.

Freeze committed versions of these four dependencies for the revised packet; do not select the held unrelated drafts as a prerequisite. The current review qualifies the actual called HEAD-equivalent code, not the entirety of the held dependency rewrites.

## Traced behavior that is coherent

- Source-specific requests retain bounded days/windows and limits. Unsupported Activity/Pressure returns before database access. Requested-source reads do not create demands or start sensors; unused source dependencies are not queried.
- Steps status delegates native range qualification, preserving provider logical window identity, semantic revision, actual bounds, local structural day, explicit gaps/materializing/unavailable days. It neither creates admission ordinals/WAL stages nor sums counts. Numeric totals remain with the existing count-domain composer.
- History has a real production consumer: selected `UnifiedHistoryRangeReader` routes its bounded background Steps, strict Wi-Fi/Cell and Location qualification through this façade at lines 466,512,532,563. Existing local/imported flags, requested scope, strict radio result and count-domain composition are preserved.
- Wi-Fi/Cell keep valid qualified siblings and explicit qualification gaps. Selected child timestamps are restricted to the requested half-open measured window, without changing parent identity/bounds or relying on batch delivery date. They do not require Location.
- Location status uses bounded receipt discovery and the retained source-owned qualifier. It reports only native Ambient scopes, retains independently valid native authority alongside an imported copy, and requires neither radio nor Steps. Legacy/imported visibility remains a separate History concern.
- Current Off state does not revoke retained historical grants. Data epoch, deletion, retained floor, source-owned child/receipt integrity and active retention settlement continue to qualify saved data.
- Each source read owns a Room transaction and its own snapshot. The coordinator does not advertise a fabricated common snapshot across separately read sources. It isolates requested-source failures, propagates cancellation and conflates invalidations without a timer or cache.
- Hilt binds the existing stats/API-facing seam through stats/data. Engine depends on stats/api, not stats/data implementation. History uses the same concrete façade in its existing module; test constructors retain compatibility.
- The selected observer adds cursor, gap, authority, retention, receipt and retention-operation dependencies to the existing History invalidation list.

## Evidence limits

The packet inventories 32 newly authored source scenarios: 10 Steps Room, 5 radio Room, 5 Location Room, 2 native radio child Room, 7 coordinator and 3 API contract cases. I inspected their source and existing fixtures. They are all **NOT_RUN**. AD2 limits one named claim until corrected. The DTO/source seam does not prove collection readiness, whole UI integration, all source subsets, physical lifecycle/power behavior, compilation/Hilt/Room generation, migrations or device readiness.

