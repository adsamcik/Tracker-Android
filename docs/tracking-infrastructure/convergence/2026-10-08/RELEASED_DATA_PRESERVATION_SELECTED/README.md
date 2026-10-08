# Approved released-data preservation — private source selection

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. All thirteen authored cases are NOT_RUN.**

Input: `18141c7ea01807ad63807915085b19bda05ef8ba`. Released main input:
`59fda665c7099d10b16aa60f505de88bd05d3131` (Room version 12).

The human explicitly approved preserving the reviewed old fields and records on October 8:
"Yes" and "migrations should leverage import export workflow so that we can do it more
cleanly and reliably." The authoritative answer is recorded in the committed October 7
`EXECUTION_DECISIONS.md` under **October 8 explicit answers**. The older V2 proposal remains
unchanged as historical review evidence; its NOT_APPROVED label is superseded by this answer.
This packet is authored privately and awaits independent review and root selection.

## Selected behavior

- Restore the seventeen approved nullable original fields on their existing parent records.
  New recordings leave them null. Preserve original doubles, string network codes (including
  leading zeros), first-seen times, source IDs and session choices without changing canonical
  coordinates, source meaning, IDs or recording permission.
- Declare the existing normalized rejected-session and count-only Wi-Fi tables in Room.
  Restore the released Room annotation on the same NetworkOperator class and register it.
- Extend the existing LegacyV26Importer table copy inside LegacyImportRoomCallback's fresh
  Room transaction and the existing original-file family lock. Reuse the existing
  ImportJobReceiptEntity completion status and job receipt. No startup export, added job
  engine, added helper or dependency on feature/import-export.
- Reuse the existing `segment:<id>` organization adapter for original session labels.
  A missing catalog label keeps its raw reference; no detected activity, name, zone or
  bounds are invented. Reopen preserves later user edits.
- Permit recognized normalized input lacking optional original columns or either raw
  compatibility table. Missing original values stay null; missing tables contribute zero
  rows. A present malformed table still fails the same atomic import.
- Include the approved fields and tables in the existing unshipped 28→29 assembly and
  containment checks. Existing incomplete/unknown developmental databases remain preserved.
- Delete the three preserved tables through the existing full-clear transaction, retaining
  the legacy completion marker. Existing guarded trip/radio retention owns the two timed
  tables. Unknown rejected bounds stay unknown; untimed operator names are not time-pruned.
- Page count-only Wi-Fi by its original measured `(time,id)` using the existing keyset
  convention. A limit is a page size, not a whole-history cap. No access points or sessions
  are fabricated from counts.

## Evidence and selection

`BASE` contains exact committed input source. `SELECTED` contains the fifteen chosen paths,
all UTF-8 LF. `WORKING_BEFORE` captures raw live bytes without modifying them. Differences
from raw working bytes, including line endings, do not authorize any mixed-draft selection.
`FILES.json`, `DEPENDENCIES.json`, `SELECTION.patch` and `TEST_INVENTORY.json` pin the source.
Forty-five candidate dependency paths and ten released-main paths are captured.

Only the root may select or commit this source after independent review. Authoring scripts
write inside this private packet and are not application source or validation tools.

No source gates, compilation, tests, schema generation, device/provider operations, Git
mutations or integration were run. Room v29 JSON is absent at the input commit; its generation
and the migration test's schema validation belong to the final convergence batch.

## Still separate

- There is no all-Wi-Fi/all-Cell legacy erase caller. Purpose-scoped source erase cannot claim
  unknown-purpose old counts/operator names; do not connect them to an ambient consent reset.
- DefaultTripRepository.deleteTrip → TripDao.deleteById removes only the segment. Existing
  organization annotations can outlive that direct path; parent retention/full clear are
  covered here, and direct trip deletion cleanup requires its separately reviewed slice.
- History/UI and typed radio-file consumers have not been changed to display/export the new
  raw compatibility records or extra original fields. Their exact stored shapes are ready
  for the shared source-owned readers; portable wire changes are not implied by storage.
- Older active unshipped databases that already lost originals do not acquire invented
  values. A supported 28→29 upgrade adds nullable fields; unknown/incomplete v29 remains
  contained. Released main12 imports preserve the originals in the fresh active database.
- Full app backup restore, schema convergence, physical provider proof and dev/v10 integration
  remain separate. This packet does not declare the assembly complete.
