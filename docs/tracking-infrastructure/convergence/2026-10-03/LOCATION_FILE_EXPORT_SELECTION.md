# Saved Location file export selection

2026-10-03. **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED**.

This selects the approved ordinary Location data backup behavior. It is not native
recording authority, a signature, permission granted by an import, or complete
session-bundle membership. The user's files remain structurally editable.

## Exact source selection

- `LocationFileExporter.kt`: complete current tracked delta, SHA-256
  `30b9159acfbb139c0ec4b636cb8ecf270929802646aa701ce12a85a0ab7f3c16`.
- `LocationPurposeDao.kt`: **only** `LOCATION_FILE_EXPORT_QUERY_SELECTION.patch`.
  Before/after snapshots are in `location-file-export-selection/`; other History
  rows/queries and registration-plan drafts are excluded. The full live DAO pin,
  including excluded drafts, was
  `42ff74fcf6e0116dd600f962d9e86c3e2a3d3457b6adfb7cf29ae99bf223b8ed`.
- `LocationSavedFileSelectionTest.kt`: exact new file, SHA-256
  `a00fe03846d3478348fbb6006d1904e2dc084ad04ac8a473d244936ccc657157`.
- `LocationFileExporterTest.kt`: complete current tracked delta, SHA-256
  `e9acaceedc7d697bb958d1d89e5de0c9f954415a74bc306f1bd9926f0a97f728`.

## Behavior and review

The saved-point query includes coordinate-free and older saved points, including
points whose receipt remains but whose original purpose information is missing.
Missing information becomes Unknown origin. Existing retention floor, settlement
guard and physical deletion still apply. Current recording switches do not hide
history. No source proof is required by this user data file.

Reuse existing `FileBackedExportSpool` and its owned cleanup. The complete file and
stable point identities are captured in one Room transaction. Release that snapshot
before copying to the destination; check original caller access and the existing
retention/deletion stamp before and after copying. Cancellation propagates; caller
stream closure and failed destination cleanup remain in the transfer child selected
at `9d48e176b050bdc88b8c2ff68fcd6c11b950f49f`.

The Location codec's existing 256 MiB file ceiling and point ceiling are unchanged.
Shared spool reservation and concurrency limits are reused, so an exhausted spool
budget returns failure rather than widening its storage allocation. There is no new
format, backup authenticity policy, collector, database table or lifecycle framework.

Independent review-only agent `/root/fresh_bundle_context_review_0930` inherited
the original user messages, follow-ups and approvals and inspected the production
caller/identity/retention/deletion boundaries and authored source. No remaining
concrete production defect found in this selection. Two test-source defects
(suspend codec call and private constant access) were repaired and statically rechecked.

## Authored, unexecuted scenarios

Eight new cases use actual Room rows, the actual Location codec and outer stream:
unknown purpose, equal-timestamp paging/stable IDs, retention plus physical erase,
destination outside snapshot with a concurrent saved row, empty selection, access
closing after staging, retention changing during copy, and caller cancellation.
The expanded existing saved-origin case includes background data without a current
policy/consent setup. It does not execute an actual settings toggle.

No compilation, Hilt/Room/schema/migration, host/UI test, Android SAF, provider,
device or convergence validation ran. Location import Replace, exact session
selectors, held source-choice UI and the complete assembly remain open.
