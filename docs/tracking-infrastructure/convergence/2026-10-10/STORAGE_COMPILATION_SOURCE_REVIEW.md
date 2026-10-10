# Independent source review for verification repairs

Reviewed by the review-only `location_gap_fresh_review_1010` agent with the original
user requests, approved decisions, exact candidate and current changes. No recursive
delegation or reviewer-executed gates. Source acceptance does not mean tests pass.

## Accepted scope

- Generated Room schema 29: SHA-256
  `e6f22b6dda00e8d8d8bf05d52c558e839a3f3f5b138b6966c4094f034ac5b82c`,
  identity `afacace9401e581a90502da821d4d95a`. Compared with schema 28 and the
  production migration: 16 new tables, 27 additive columns and one unique-to-nonunique
  index change match the SQL, defaults, primary/foreign keys and index declarations.
  Fresh creation and migration install the required custom triggers.
- `AppDatabase`: the existing private clear helper becomes suspend; its sole caller
  remains inside the same suspend Room transaction, with unchanged clear ordering.
- `CellCapturedPortableTransfer`: null and blank lifecycle boot IDs are rejected by
  the existing validity guard using `isNullOrBlank`.
- `NativePortableOwnershipSchema`: mixed integer/string SQL bindings use explicit
  `arrayOf<Any>` to resolve Kotlin's reified intersection-type error.
- `TrackingPurposeAvailability`: radio host-loss handling compares the canonical
  source enum actually carried by the identity. Existing regression source covers it.
- `LocationSampleDao` and Location import/preview: add the already-called read-only
  ID lookup, ordered by ID; existing callers use bounded batches, and empty identity
  maps skip the query. Conflict and data-epoch rules remain intact.
- `HistoryPresenterViewModel`: a local next-cursor value fixes the cross-module smart
  cast without changing paging branches.
- Import/export build wiring: the existing Gson catalog dependency supplies classes
  already imported by the radio codec and its tests. Version and format are unchanged.
- `LegacyReleasedDatabaseUpgradeIntegrationTest`: three genuine host cases join the
  production coordinator, real repository, released schema-12 source and configured
  Room-29 importer; completion, unchanged original data, reopening and explicit retry
  are checked. It uses Framework SQLite, not the production native SQLiteX runtime.

## Limits

The new app fixture checks upgrade orchestration with Location data; the existing
fuller `LegacyV26ImportTest` and separate released Points fixture supply additional
coverage. Their executed results must be reported separately.

Replacing conflicting Location points is still explicitly unsupported in this
snapshot. The approved current-correction feature and the two pending Location gap
repairs are not completed by these compilation fixes. Full assembly, native/device
evidence, integration and release remain open.
