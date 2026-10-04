# Independent static source review — native portable ownership

Date: 2026-10-04. Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

Reviewed the original inherited user requests and approved small rebuildable owner lookup, all 13 production and seven test selections, the four mixed selected snapshots, source readers/callers, Room composition, migration/preflight, correction/pruning/full-clear seams and the separately excluded R11 Pressure bridge. No production edits, Git, compilation, tests, schema generation, device/provider activation or other gates were performed. Authored cases are source only.

## Findings on the initial freeze

**N1 — selected test requires an excluded projection repair.** `SELECTION.patch:721–740` adds `raw lifecycle intent retains caller authority needed to authenticate its checksum` to `core/base/src/test/java/com/adsamcik/tracker/shared/base/database/dao/SourceSessionDaoTest.kt`. The selected `SourceSessionDao.kt:763–805` still projects only 20 storage classes and omits `source_caller_authority_reference`. Existing `SourceSessionRawEvidence.kt:149–177,283` requires that nullable field and 21 classes. The new case therefore does not match the selected production source. The actual working projection contains a held correction; it is not part of this selection. Keep this unrelated case with a separately reviewed two-path projection companion, or exclude it. This is not evidence that the lookup needs a new authority contract. The initial packet adds 16 methods rather than its documented 15 because of this extra case.

**N2 — Activity exact-owner duplicate comparison still widens to a date range.** Selected `stats/data/src/main/java/com/adsamcik/tracker/stats/data/DefaultActivityHistoryRepository.kt:734–777` resolves only relevant owners, but `loadLocalPortableOwnershipSnapshot:835–860` then calls the date-only local reader with the earliest/latest selected entry bounds and includes every overlapping owner. An exact valid native/imported duplicate can still become unavailable because an unrelated incomplete native group overlaps that date range, or unrelated groups lie between two selected owners. The new unrelated-owner fixture places those groups outside the relevant dates and does not cover this failure. Use the existing exact native `ExportSelectedPortableCapturedActivityRequest` backend for the selected physical segment IDs and current snapshot epoch through a narrow overload of the existing local comparison reader; preserve ordinary date-reader callers. Add an overlapping unrelated invalid-owner source case. No new table, framework or ownership semantics is needed.

Initial lookup selection is **not ready for a coherent unvalidated commit** until these exact issues are repaired or explicitly separated. This does not reject the approved lookup design.

## Other inspected behavior

The new six-column lookup contains existing identity/owner metadata only. Normal fact/run inserts update it within the same Room transaction; ignored inserts do not claim ownership. Existing deletion/update triggers remove keys only after their last original source owner disappears, preserve corrected logical identities and retained run ownership, and full clear explicitly deletes the lookup. Queries use source participation, exact keys, 256-key batches and a real overflow lookahead; relevant owner/member failure remains typed. Ordinary source qualification still controls retained consent, lineage and deletion.

The 28-to-29 migration completely rebuilds from original source rows, fresh Room creation installs the same triggers, and version-29 preflight checks the exact lookup structure. No capped partial rebuild establishes absence. Unknown incompatible development databases remain subject to existing preserved blocked-open behavior. Pressure's selected shared reader compares only exact entry duplicates using its existing pure comparator; its separately recorded native run/window parity gap remains open. The held R11 bridge, general History assembly and data-only restore are not accepted here.

## Pins checked

Initial immutable base: `07d5c664918affa8cc843fd243b38032bb9c782c`; parent freeze: `640a27da4ff8edeb16311d2461ef35dd4f6d076c`.

- `FILES.json`: `eaefdf9402597d49bd026d97609ec3dd662b6651dfa37c21e0c3a45ba588b24c`.
- `SELECTION.patch`: `24a119940141b61ab19021101361bae54b4bf0372b844efea35356cf44f79c76`.
- `SOURCE_BOUNDARY.md`: `3b3b87990c19001d789b4cf26fbd660607ae88930856f2f6b36b1eed19f24baf`.
- Independently checked all 20 actual working source pins and four mixed selected source pins against that manifest. No full mixed working file is accepted by those pins.

## Separate migration-fixture companion

The narrow existing `RadioObservationChildMigrationTest.kt` companion is coherent on static inspection: it supplies minimal original run/Activity/Pressure parent rows, calls the actual migration, checks all seven resulting keys and preserves existing radio/Location assertions. It changes no production seam or schema. It does not execute full Room migration validation and does not close N1/N2.

- Companion `FILES.json`: `f7b003beb1abbce8175ebbb213550c5765be1bcb64ea208fc947ca7d426ab868`.
- Companion `SELECTION.patch`: `7539a089824de8327a844f8fe5663b9cdcfb56d151d83ac1f6a46732429eed5c`.
- Companion `SOURCE_BOUNDARY.md`: `22e83f38970459563b9d352ed6e6e279851a847303ca28a9c26f6147bdcc8e5e`.
- Before test: `eea4184e82fc1cd85b6b4d5db0ee24c0b6ffe9623314efe3ff80034ddb920c32`; selected after: `e6ac30ead4e1abcf30b3c24fa3fd3b05ee3131ed3d37e3c88fc7d67946249852`.

All 25 assembly chains, full validation and integration readiness remain open.
