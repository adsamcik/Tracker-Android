# Preserved Cell detail companions

Status: `IMPLEMENTED_UNVALIDATED`. Static inspection only; no validation or Git mutation by this agent.

Select `CELL_PRESERVED_DETAILS.patch` for the preserved hunks in:

- `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/DefaultCellHistoryRepository.kt`
- Select the whole preserved `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/ImportedCellHistoryDetails.kt` separately.

The refreshed patch against the current candidate includes only imported detail attachment in individual/source-page/shared eligibility calls, cancellation preservation, and shared eligibility read-budget propagation. The finite native child loader and exact current-revision snapshot membership are already committed dependencies. It excludes the new `selectedImportedInTransaction` R11 range bridge completely. `WIFI_IMPORTED_DETAILS_RECOVERY.patch` similarly selects only the recent detail read's cancellation rethrow, excluding Wi-Fi's R11 bridge.

Dependencies: the preserved v6 native writer/classifier/indexes and RadioObservationChildDao; the preserved imported child entities/DAO/admission; the separately reviewed Cell composer source-owned per-reading v6 qualification.

Static trace: native revision discovery -> current child ownership query -> bounded snapshot -> Cell composer; imported evaluation -> visible nondeleted runs -> exact entry/revision/run/observation child checksum and original parent measured interval -> public towers. No coordinate or native source authority is invented. Source failure does not turn invalid child payload into valid towers. The suspending imported-detail read preserves cancellation in individual, shared-page and eligible-import paths.

Compatibility follows the existing optional detail-group contract in `RadioSourceFileV2.kt`: absent groups remain valid summaries; any present group must contain the exact declared count. Each surviving child must fit its original parent's measured interval, including both uncertainty endpoints, and use the exact parent semantic revision. This applies equally to the committed Wi-Fi sister helper. No schema, detail marker or new format was introduced. Deleted-run children are excluded before checksum, bounds and count qualification.

Authored source scenarios in `ImportedCellHistoryMapperTest.kt`: production import -> exact tower details via imported/detail/shared-page calls; absent summary group; partial group; mismatched semantic revision; validly rehashed outside/uncertain/overflowing measured times; deleted-run children hidden while current towers survive. `ImportedWifiHistoryDetailsRoomTest.kt` authors the same six scenarios using actual Room parent/child DAOs and qualified-product fixtures at the source-owned detail seam. Its qualification fixture is not full Wi-Fi importer authority evidence. Existing cancellation test source remains in the Cell mapper suite; the new shared-eligible suspending wrapper's cancellation branch was statically traced.

Selection: `IMPORTED_RADIO_DETAILS_FILES.json` lists the whole companion/helper/DAO/format/test paths and the two mixed DefaultCellHistoryRepository/DefaultWifiHistoryRepository patches. Both R11 `selectedImportedInTransaction` bridges remain excluded. `R11_BACKEND_FILES.json` separately declares its dependency on the Cell preserved companion.

Fresh review corrections: helper DAOs query exact entry/revision/run and surviving observation IDs in batches of 64 using existing parent-key indexes. Deleted or unretained owner rows neither consume nor exhaust the 4096-detail materialization budget. Genuine surviving overflow returns the existing failed public product with `READ_BUDGET_EXCEEDED`, preserving qualified identity/metadata and withholding observations; Cell shared eligibility preserves the corresponding source read-budget result. The Wi-Fi recent composition wrapper rethrows cancellation before generic errors. `RadioSourceFileV2.requireCellChild` checks the six current `CellHistoryTechnology` names, so a validly rehashed unknown type fails file admission consistently with history.

Additional authored sources: both Room suites exercise over-4096 hidden children with valid surviving details and real surviving detail overflow; Wi-Fi recent production composition is interrupted after qualification and must not return a failure page. The V2 codec suite covers all six accepted session/background types and unknown/lowercase/empty types, including a checksum-valid edited file rejected by the real codec. No new schema, exception framework or compatibility marker was added. All tests remain unexecuted.

This is a narrow companion selection receipt, not R04/R11 full completion, a frozen convergence input, or passing test/device evidence.
