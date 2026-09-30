# Ordinary retained Wi-Fi/Cell authority

Status: **IMPLEMENTED_UNVALIDATED**. This is production/test-source authorship and static tracing only. No build, Hilt, Room compilation, tests, schema generation, device, battery, CI, integration or activation was executed.

## Scope and trace

The ordinary range/day radio readers previously checked fact and child checksums but could show native rows without their retained recording authority or imported rows without a surviving receipt/current data epoch. They now share bounded qualification with optional radio maps.

- `RoomAmbientWifiRepository.kt:63` and `RoomAmbientCellRepository.kt:66` call the qualified snapshot seam. Public history and portable export retain existing result types: dependency overflow is explicit, unverifiable authority is unavailable, and erased/expired/old-epoch records are withheld. Turning recording off does not revoke saved history's original grant.
- `RetainedAmbientRadioReader.kt` reuses the existing `RetainedAmbientRadioAuthority` provider/demand/authorization/policy/consent checks and legacy aggregate-owner compatibility. Exact current cursor, contiguous correction lineage, authentic retention approval and source deletion/retention fences are checked for the reading and its referenced owner. Previous-epoch/expired candidates are filtered before removed recording context can cause an unrelated failure.
- Acquisition gaps authenticate their own historical consent/retention/checksum/deletion tuple. They do not invent a provider observation or require provider evidence they never stored.
- `RetainedImportedAmbientRadioReader.kt` qualifies correction ancestors and gaps using the actual import receipts, current data epoch, portable retention approvals, archive tombstones and persisted identity/effect/archive replay footprints. Native sensor permissions or current recording choices are not import authority. A later correction cannot revive an erased or expired ancestor.
- The injected ordinary readers obtain the existing shared `BootClockDomainProvider`. The two-argument constructor used by historical tests has no authority to treat an open provider as current; finite historical registrations still work.
- `RoomRadioLocationContextRepository.kt` now calls the same source-owned qualified seam. It carries explicit ambient read gaps while retaining independently qualified native rows and capture siblings. Duplicate provider/retention/lineage qualification was removed from the paired reader. The redundant paired-reader Boot dependency was removed and its test call sites updated.
- Public entry points use the existing injected IO dispatcher and one Room snapshot. Paired/history callers use the in-transaction seam. Portable exports already consume the ordinary reader inside their own snapshot; no transfer/storage format was changed.

No new dependency, entity, migration, network access, collection request, user-facing proof, map notice, import permission or backup restriction was introduced.

## Exact production manifest

1. `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/AmbientWifiFactDao.kt` — four bounded retained cursor/receipt/tombstone/approval queries only.
2. `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/AmbientCellFactDao.kt` — corresponding four queries only.
3. `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/RetainedAmbientRadioReader.kt` — new shared native/gap qualification.
4. `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/RetainedImportedAmbientRadioReader.kt` — new portable qualification.
5. `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/RoomAmbientWifiRepository.kt` — injected Boot seam, shared qualification and strict public result handling.
6. `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/RoomAmbientCellRepository.kt` — same Cell wiring.
7. `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/RoomRadioLocationContextRepository.kt` — shared ambient seam/extraction only; captured and Location matching rules preserved.

The two native `toApiFact()` measured-lower-bound hunks in production items 5/6 belong to the separately authored `AMBIENT_RADIO_MEASURED_BOUNDS_FILES.json` companion. They are preserved here, not independently reauthored.

## Exact test-source manifest

1. `stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/RetainedImportedAmbientRadioReaderTest.kt` — new actual Room imports/receipts: permission independence, missing receipt, epoch/floor, tombstone/footprint, public range/day and portable export.
2. `stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/RetainedAmbientRadioGapReaderTest.kt` — new actual Room historical gap grant, Off, deletion, epoch, retention and missing grant cases.
3. `stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/RoomRetainedAmbientRadioContextTest.kt` — preserve paired owner/consent/deletion scenarios; add ordinary range/day Off history, previous-epoch context removal, changed cursor/sibling independence, injected current boot and discovery overflow. Update paired constructor call.
4. `stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/RoomAmbientRadioDetailCompletenessTest.kt` — upgrade historical provider/demand/auth/policy/consent/retention fixtures so the existing missing/exact/legacy child assertions still isolate detail completeness.
5. `stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/WifiHistoryRepositoryRoomTest.kt` — only four paired constructor calls remove redundant Boot argument.
6. `stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/CellHistoryRepositoryRoomTest.kt` — only three paired constructor calls remove redundant Boot argument.

No test was run. Existing `RoomAmbientRadioPortableTransferTest.kt` uses actual import/receipt fixtures; it was statically inspected and not edited. The measured-bounds companion test uses direct Room row conversion, not an unauthenticated ordinary reader.

## Pending work and review

### Coordinator repair companions

The independent source review found and the coordinator repaired three concrete regressions:

- Current retention cutoffs now apply to the effective selected fact and aggregate owner, while
  every correction ancestor still requires its original receipt/approval/checksum and no explicit
  erase. Native and imported Room cases cover range/day/map/export and surviving original lineage.
- Ordinary native and imported details must fit the parent reading's measured interval. A shared
  small predicate uses uncertainty and overflow-safe bounds. Real Room cases keep complete child
  counts and recomputed checksums but move a child outside its parent, then check unavailable reads
  and exports rather than accepting that detail.
- `RetainedAmbientRadioAuthority` preserves dependency overflow as a typed internal outcome; the
  existing ordinary result remains `DependencyOverflow`, and paired reads carry `DEPENDENCY_LIMIT`.
  Loader tests cover provider and authorization limits separately from fact discovery limits.

Additional selected companions are `RetainedAmbientRadioAuthority.kt`,
`RetainedAmbientRadioAuthorityTest.kt`, and `RoomAmbientRadioPortableTransferTest.kt`.
`RETAINED_AMBIENT_AUTHORITY_FILES.json` records the complete selected source input.
The independent recheck and all execution gates remain separate; these are authored, unrun sources.

- Exact archive member selection remains pending the archive-membership storage decision. `archive_id` on deduplicated fact rows cannot identify every member of an overlapping second import. This slice deliberately adds no date/archive-ID substitute and no `readArchiveInTransaction` assertion of completeness.
- Partial-map notice UI remains pending its user decision. The internal qualified seam preserves explicit gaps for that work without adding copy or a public status model.
- Independent source review and all deferred convergence gates remain required. This receipt does not declare whole assembly, backups, bundles, Location replacement or device readiness complete.
