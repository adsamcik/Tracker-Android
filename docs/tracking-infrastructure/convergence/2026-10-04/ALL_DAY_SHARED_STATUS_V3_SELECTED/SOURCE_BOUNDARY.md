# Shared all-day saved-data reader

**IMPLEMENTED_UNVALIDATED — source authorship and static trace only.** No compiler, Gradle, tests, lint, schema generation/drift, diff check, CI, devices, provider activation or Git operations were run by this agent. This is one bounded source slice; it does not close the assembly gate or establish runtime/integration readiness.

## Authority and selected behavior

The user approved one small read-only stats API connected to existing source-owned all-day readers (call_l1Jrri8zloCOyAiJug3x55If, item 2). Root and the independent reviewer agreed History is the existing real consumer. The latest user direction prioritizes completing the tracking architecture and system over backup work. This slice implements no backup behavior.

- The new `AllDaySourceStatusQuery` returns source-specific saved-data metadata. Steps retains actual provider window IDs/revisions and stored local days; it never invents a WAL event, admission ordinal, or Recording phase.
- Wi-Fi/Cell reuse their retained native qualification and measured observation-window selection, preserving valid siblings and typed qualification gaps. Metadata carries only clocks, identity/revision and saved coverage. Child timestamps are restricted to the requested half-open measured window; parent identity/bounds remain original.
- Location reuses bounded `contextReceiptsOverWindow` and `RetainedLocationContextReader`, then selects independently qualified Ambient scopes. An imported copy cannot mask an independently valid native grant. Imported/legacy-only coordinates never establish native saved-data status.
- Activity and Pressure explicitly report unsupported sessionless products. Unsupported requests do not require storage or query a provider.
- Collection availability remains the existing capability/applied-plan status. This interface never claims a provider is currently recording and never changes participation, consent, quality, collection, schedules, authority, stored data or count totals.
- Existing source limit, corruption, storage, epoch, retention and gap outcomes remain explicit. Cancellation propagates. The coordinator isolates failures by requested source and reuses conflated Room invalidations without a timer or new state store.

## Actual connections

`StatsDataModule.kt` follows the existing internal Hilt-module convention for the new binding. `UnifiedHistoryRangeReader` delegates its existing background Steps, strict Wi-Fi/Cell and Location qualification call sites through the shared concrete facade. Its constructor keeps the existing source dependencies/default fixture compatibility. History retains original requested local/imported/legacy membership, measured date bounds, strict radio failure semantics and the existing Steps count-domain composer.

The lifecycle coordinator retains its existing session path and adds bounded all-day delegation. The observed-table union adds accepted Location receipts/purpose/retention and Steps/radio cursor, gap, authority and retention tables, plus the existing retention-settlement barrier table. A gap/cursor-only change can wake the reader without a new fact or a fabricated common snapshot. Each source result keeps its actual read snapshot.

## Frozen inputs and exclusions

- `FILES.json` pins 13 selected source/test paths. The original seven BASE files were exported from `32c8cd8fe60bf77457b83dbc84694188e485e3d6`; extra fixture BASE files came from `1056464a1376e3574f3a744a6eaf5e3402accaf7`. Root confirmed original selected BASE inputs did not change between those commits.
- `BASE`, `WORKING_BEFORE`, `WORKING_AFTER` and `SELECTED` preserve exact independent inputs. `AUTHORING.patch` records this agent's authored before/after delta. `SELECTION.patch` records the narrow HEAD BASE selection. Selection must use SELECTED, not whole working files.
- Existing Stats module text-encoding edits, held Location History tests/reordering, and the held native-radio History midnight test/imports remain excluded and preserved. `EXCLUDED_WORKING_DIFF` records them.
- The existing native radio detail fixture's offset/zone helper extensions and Instant/ZoneId imports are a necessary selected test-only companion for the two real native-child cases. The case inventory describes this explicitly. No second native-child harness was added.
- `AmbientRadioMeasuredBoundsRoomTest` was inspected and preserved but remains unselected and unchanged: it has parent-bound mapping fixtures, not the complete native child/authority harness needed by status.
- `DEPENDENCY_INPUTS.json` pins 57 immutable source dependencies and copies their exact bytes into DEPENDENCIES. Those are not selected edits; root must reconcile any candidate dependency differences with the full assembly inventory. They preserve the source-owned readers, API models, DAOs, authority/child contracts, DI and module graph used by this slice.
- The first source selection is preserved at `../ALL_DAY_SHARED_STATUS_SELECTED`. V2 corrects one statically found new-facade import: RadioContextScope is its existing stats/data internal type. The first selection was not reviewed or executed and must not be staged.

## Test-source inventory and evidence limit

`TEST_INVENTORY.json` lists **33 NOT_RUN** cases: 10 Steps Room, 5 radio Room, 6 Location Room, 2 native radio-child Room, 7 coordinator and 3 API contract cases. They exercise actual provider windows without a session/WAL event, correction/retraction shared with History, explicit gaps and cursor-only progress, disabled collection preserving history, native-only membership, local DST/calendar failure, corrupt/closed storage, radio sibling qualification and exact limits/epochs, native Location/import coexistence, mandatory radio children and measured midnight filtering, requested-source laziness/isolation, cancellation, conflation and invalidation dependencies.

These are authored scenarios, not passing results. Invalidation-table membership plus actual cursor/gap reads are source evidence; no live Room observer/device claim is made. The full convergence gates, physical providers, lifecycle/battery evidence, all 25 full chains, and integration into local dev/v10 remain outside this packet and pending.


## V3 independent-review repair

V2 and its independent review remain preserved. AD1 makes the existing top-level
History SourceEvidenceState.hasValidRangeShape predicate internal and invokes it
immediately after the facade reads the state. The facade refuses malformed
authority before constructing a snapshot or consulting a source reader. No new
validator or authority contract was introduced; existing History behavior is
unchanged. The 33rd actual Room test first reads a valid native point, then tests
negative revision, epoch, retention floor, erased high-water and update time
independently, with exact stored point/state preservation and restored positive
reads between cases. All 33 cases remain NOT_RUN.

AD2 narrows the unverifiable Location fixture's name and inventory claim. Its
foreign-ID sample is legacy, not an authenticated portable import. The separate
real imported-copy/native-sibling fixture remains unchanged.

Four dependencies now use root-exported HEAD-exact source: LocationPurposeDao,
RadioObservationChildDao, AmbientStepsFactRevisionDao and
DefaultAmbientStepsHistoryRepository. Independent review confirmed their held
latest-registration/export/recent-cursor/session-reader draft differences are
unreachable through this approved background call chain. Their working files
remain untouched, and V2 preserves the prior frozen dirty snapshots.

AD1_REPAIR.patch and REPAIR_WORKING_BEFORE preserve this narrow review response.
Select the thirteen V3 SELECTED snapshots against the original exact BASE pins;
do not stage whole working files or held dependency drafts. No tests, compiler,
schema generation, validation gates, Git or providers were executed.
