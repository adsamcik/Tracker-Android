# Radio aliases V1: partial-name finding

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED; all seven new test methods NOT_RUN. Source review only. No build/test/schema/provider/device/Git/integration operation ran. V1 is held pending this narrow repair.

Exact FILES9c2c24a4377eab4dc96f42cc64eeeedbc7c5a63497041c6126d22997166e057c; patch e9d55dd7c489d3e5442ebe7f5b43f1107fafcfea139a60a7c8623cae75343eaa;32depsd007765b473bcd63163da5562e98c890d25ba1211f8dfd3eb5c0255b43eddee8. All six BASE/SELECTED/WORKING_BEFORE hashes and32 current committed dependency pins match. V1_INPUTS.json freezes the evidence metadata.

## Required failure-isolation repair

MeasuredCapturedRadioHistoryReader.importedNativeMembership accumulates valid native alias positions across imported runs/observations. A later call to existing measuredImportedWifiChildren or measuredImportedCellChildren can fail because one parent's child list/checksum is invalid. The actual imported product evaluators authenticate the envelope/summary separately; Readable does not establish the later child checks. Both existing detail helpers check exact child membership/count and checksum and can throw in this state. The exception exits the complete membership operation, so MeasuredSessionNamesReader's selector-level catch retains all readings but receives no accumulated alias positions: independently valid sibling session names are lost.

Keep the failure local to each original observation and return the existing typed metadata problems together with accumulated matching positions. Propagate cancellation. The names decorator can merge valid positions and source-local problems; no new recording permission, store, public facade, file format or provider demand is needed. Author confirmed the actual evaluator/child boundary and proposes an actual Cell two-parent corruption/surviving-sibling Room case, authored NOT_RUN. Preserve V1 before the repair.

## Other reviewed seams

The original imported/native duplicate predicate is preserved exactly, now returning its independently qualified parent and child rather than a Boolean. It requires the real original entry/run/observation identities and complete original child values. It never matches timestamps alone. The unchanged SQL full physical tuple maps the matched captured original to the existing canonical native position; that position is shared with a displayed independently qualified Background sibling. Background receives metadata only, never Capture authority. Explicitly erased/unreadable captured originals cannot establish alias membership. Origin filtering and original run/observation retention remain, and divergent user-edited imported values stay separate.

Actual UnifiedHistoryRangeReader constructs the same captured radio reader inside its existing qualified Room snapshot and passes it/range to the names decorator. Existing constructor callers/test helpers are included. Annotation pages and original counterpart pages remain bounded, check progress/cancellation and do not impose a whole-history name ceiling. Source-local failures keep already-qualified observations visible.

Seven cases cover actual Cell importer/native full-child qualification and the explicit Wi-Fi imported evaluator fixture. SQL/Room/coroutine/UI/provider/performance execution remains NOT_RUN; seeds are not provider/writer activation proof. All25 prerequisites/T25 and held shared setup remain OPEN.
