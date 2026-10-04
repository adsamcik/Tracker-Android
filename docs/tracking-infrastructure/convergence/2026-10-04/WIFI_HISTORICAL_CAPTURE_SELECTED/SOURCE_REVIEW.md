# Independent source review — Wi-Fi historical capture

Status: **BLOCKED — one concrete shared-settlement authority gap.** Static review only; this packet must remain preserved as first-attempt evidence.

## Scope and phase

Reviewer: `/root/fresh_radio_schema_review_1004`, independent of the author and coordinator. Reviewed the full inherited user requests and approved decisions, current AGENTS.md, this packet's BASE/SELECTED snapshots, selection diff, dependency graph, source boundary and authored test inventory. No recursive delegation, production or test-source edits, Git mutations, compilation, test execution, lint, schema generation/drift, CI, device/UI/battery work or provider activation was performed.

This is an IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED receipt. All 25 full-chain assembly prerequisites remain open. Authored tests are not passing-test evidence, and this review is not assembly completion, integrated validation or readiness for dev/v10.

## Exact input

BASE: `c053ec16d4c2fc2cdf6609331de98c0d00cce2e6`.

Verified SHA-256:

| Artifact | SHA-256 |
| --- | --- |
| PACKET_PINS.json | 590d3af54205d442534b09533d6db42ce00c4ce0513aa40a2a462544a6208a69 |
| FILES.json | b2ba874f1db8c70bb07a50657c15deff3125656f5fba0c39387e6a2274e36fbe |
| SELECTION.patch | 9fbc0f45abff0720b71cc004da50627f97a625d0011812103a03914a3f060f49 |
| DEPENDENCIES.json | 26880ccf255aea33361ace473b2f8f72dd213c35c10190c9c7a197035a296693 |
| SOURCE_BOUNDARY.md | f693cd828bc75a0717a03db9a344307a12d77f70252c5e7f291b71160a526b3d |
| TEST_SOURCE_INVENTORY.json | 334613e93e8394d3dbfcf79b374b3ff112e1515f873f4260ea58b5dcba699e52 |

All 14 BASE and SELECTED file hashes match FILES.json. All 64 frozen dependency hashes match DEPENDENCIES.json; the three revised schema-companion artifacts are located under DEPENDENCY_INPUTS/schema_companion. This review uses these exact selected bytes, not the moving working files.

Selected file pins (paths relative to the repository):

| File | Selected SHA-256 |
| --- | --- |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/wifi/WifiWalQualificationAdapter.kt | 412e5ff84a7422925c51214798e37e4222451731db87fd0e8b297fe35d6b9ca7 |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/wifi/WifiCapturedFactClassifier.kt | 05790ab20032282d77ae6e3b511846626acdfa258e791b81f7682fc211120adb |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/wifi/WifiCapturedFactWriter.kt | 57a2afac28c2d68538f551ba1ac473ecee8dd6985dbce0af5132aedb59de7a20 |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/wifi/WifiSessionFactProjectionLane.kt | 6b7163b981bc490b63f645b949f378cde724eea64e5bb220d42a924a8aaedf60 |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/wifi/WifiCapturedFactMaintenance.kt | 3d24ab860f43c4e61c945da41af5a30d9e2fa524d1d168be98b1086d472aeb7f |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/wifi/WifiCapturedPortableTransfer.kt | 975950d617185a649ad25735b31798d81619a92f335729724c367808f2d415a0 |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/wifi/WifiCapturedFact.kt | 7b5c0810465c1f623bf278be4b541c2ac78f5fea7c148e39f61db9b2065567ab |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/lifecycle/DefaultSourceLifecycleProofAdapter.kt | feee8953fecc4ee59904feea13c64222de2f21ed27bc13eaf762d8a94aefb8f6 |
| core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/WifiWalCarrierAuthenticator.kt | dc076b99abcbd7fdca1e6610ee42840caf9f885b55835b9a827e381e31c34c52 |
| core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/WifiCapturedFactDao.kt | f7624c5c5e1e20612e97f7b8687b172daa77aee78290930e4c173939215449a4 |
| stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/WifiHistoryComposer.kt | 5d38b77029a6af21aab516e324c28f4cedb1005d9e3112055366029025141778 |
| stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/DefaultSourceFactQueryRepository.kt | f64e96b68dd80f9e04706862055de3453579ba0dc7516abcc7c4851e318def30 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/wifi/WifiWalQualificationAdapterTest.kt | e045d61f3fb85633dbdb094e4f8d3c68dcffb5abc74d385341dd9ed5ec885584 |
| stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/WifiHistoryRepositoryRoomTest.kt | a6159a7c54e4a81c4fb72abfe47b479c2b227d62fdf9970d35e667833cbca0a9 |

## Blocking finding W1 — ACTIVE shared owner lacks acknowledged selected-run settlement

**Evidence in the selected WifiCapturedPortableTransfer.kt:**

- Lines 424–450 accept positive completeness using hasPortableRegistrationShape, hasExactPortableCaptureRetirement, exactPortableActions and hasExactPortableTail.
- Lines 741–743 allow an ACTIVE physical registration merely from its active registration shape. Lines 762–784 authenticate source STARTED/START_ACCEPTED actions; these are not stop/drain acknowledgments.
- Lines 857–883 correctly narrow per-reading capture demands to the selected run and require each retired demand to match its closing authorization. However, no source_registration_retirement / rawRunRetirement authority is loaded or compared. Lines 887 and 897 deliberately skip the old global barrier/continuation constraints for per-reading exports.
- Lines 1038–1082 accept COMPLETE from appDrainComplete and no unresolved gap; neither the callback-entry barrier nor an acknowledged run retirement/cutoff is correlated with that assertion. Empty tails at 937 can be accepted without any WAL evidence.

Thus a retired selected demand plus closing authorization and a claimed COMPLETE completeness row can export a finished empty session while the physical registration is ACTIVE even if no selected-run retirement acknowledgment exists, is still pending, or has a different cutoff/tail. Demand retirement is intent/authorization closure; it does not establish that provider callbacks were drained through the session cutoff. This undermines the approved acknowledged-retirement requirement and can declare a session's empty/complete export before the shared callback prefix has actually settled.

The new positive test demonstrates the missing prerequisite at source level: WifiWalQualificationAdapterTest.kt:766–778 exports an ACTIVE registration after deleting all WAL rows. installHistoricalWifiGroups at 1026–1028 saves only completeness; it does not save a SourceRunRetirementEntity. The negative case at 779–783 checks demand/closing mismatch but not the missing acknowledgment.

The already accepted Cell R10 implementation provides the existing seam: CellCapturedPortableTransfer.kt:1666–1671 obtains exact rawRunRetirement for the selected logical/run/source/instance/generation and validates it against completeness; 1800–1816 require ACKNOWLEDGED, complete drain, exact lease/tail/coverage, callback barrier, supported shared removal/flush outcomes and bounded cutoff; 1768–1796 correlate the final selected closing authorization and last selected demand retirement with that cutoff.

**Required narrow repair:** reuse existing selected-run retirement authority for the ACTIVE per-reading Wi-Fi settlement path, correlate completeness and final selected closing cutoff, retain earlier legitimate policy-demand retirement times, permit unrelated active demands, and preserve the existing physically retired path. Author actual Room negative cases for missing/pending/mismatched acknowledgment and cutoff/tail/lease identity plus a valid acknowledged empty export and independent active sibling. No new storage/public architecture is needed.

## Other reviewed behavior

No additional concrete blocker was found in the remaining selected production changes:

- v6 qualification preserves original callback WAL identity, ordinal, raw headers and payload, then independently qualifies each historical purpose from measured item timestamps. The last timeline member for an unchanged demand avoids clipping readings across an unrelated authorization rotation; new-session consent is not lent backward.
- The group writer/projection lane processes every valid group in one existing Room transaction. Cancellation propagates and rolls back facts, children and lane progress; deleted/retained group dispositions can settle independently while terminal authority failures remain visible.
- Retention/replay/source erase selects exact logical/run/manifest/policy/consent facts, accepts same-event policy siblings, and keeps the separate raw ordinal/integrity collision fence. The coupled revised radio receipt index is NONUNIQUE only in final schema29, preserving existing primary identities.
- Required v6 network children are authenticated against their own raw carrier with bounded 65-row reads (64 supported children plus sentinel). Aggregate reuse additionally authenticates the owner carrier and required children; lifecycle, exact lookup and history do not borrow missing or corrupt owner evidence.
- DAO reverse lookups apply requested purpose/run/policy/consent constraints before bounded receipt selection. Ambiguity remains an unavailable/failure outcome rather than an arbitrary receipt choice.
- Native history admits historical facts from a denied or subsequently owned callback without rewriting the callback header; saved network observation timestamps survive late delivery and midnight crossings.
- The selection preserves committed Cell R7/R8 helper repairs. WifiCapturedFact.kt is intentionally unchanged; held contract drafts and held history fixture foundation are excluded. Shared R11 origin predicates are intentionally excluded for later exact composition, while Wi-Fi's own manifest/action origin checks are prepared-only as intended.
- Existing bounded snapshot, query-budget, typed error and cancellation paths are reused. The group result variants carry the necessary per-purpose results without adding a new storage/owner framework.

## Authored test evidence

The inventory contains 15 new authored, unexecuted cases: 12 adapter/ingress/writer/lifecycle/retention/export cases and 3 native history/exact lookup cases. They exercise real Room, actual ingress/writer/lane and source proof entry points, including historical split admission, malformed sibling preservation, cancellation rollback, unchanged authorization rotation, same-run native policy/consent groups, source/session erase, retention replay, terminal failure, missing owner children/carrier and measured-date preservation.

The shared empty-export positive fixture lacks the required acknowledgment (W1) and must be repaired. Other authored fixture structures were statically traced; no claim is made that any case compiles or passes.

## Disposition

Preserve this rejected frozen packet and receipt. Re-review the narrow settlement repair and its exact composition with this input and the separately accepted R11 predicates before selection. No whole-source or whole-assembly completion is declared.
