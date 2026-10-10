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

## Repairs from the second executed pass

The same independent reviewer accepted the exact additional production and test
diff before commitment. These changes require an actual rerun; the first results
from that pass predate the repairs.

- `AuthoritativeTrackingParamsRepository`: an already-aligned source-policy snapshot
  no longer waits for the mutation lock held by a blocked compatibility mirror.
  The lock now surrounds only the existing current-state/revision check and setup
  mutation. Both callers delegate locking to the helper. Existing revision,
  reconciliation and Ready-generation checks remain; the existing blocked-mirror
  regression test exercises the publication contract. Reviewed file SHA-256:
  `4f709fa242d937dff9c430011c92ce60d890219527de16c868a8c24a142de6f5`.
- Core database test bindings use explicit `Any`, and session manifest fixtures
  provide their existing run identity. Legacy migration preservation assertions
  are unchanged.
- Portable Steps graph fixtures use valid, distinct hexadecimal identity strings;
  production identity validation and graph assertions are unchanged.
- Retention fixtures select the approved unified Steps choice. Policy-change
  fixtures enable default-off Wi-Fi so a real change occurs. The isolated bootstrap
  storage-failure fixture turns Steps off to match its fake revoked retention.
- The legacy fingerprint fixture follows the actual canonical ordering of Location
  minimum time and distance. This changes the erroneous fixture, not production
  fingerprints.
- Notification accessibility reads custom actions from `SemanticsActions`; the
  Pressure history fixture supplies its required local origin.
- Tracker launch assertions match the precise current reservation branches inside
  the application-scope launch. Location availability exercises both acknowledged
  adaptive and passive degradation, retaining the same missing/wrong identity,
  zero execution and invalid reason rejection checks.

## Notification localization repair

The independent reviewer accepted 39 additive files named
`tracking_notification_completion.xml` under the existing regional resource
folders: 224 translations of 16 existing English resource keys. XML, duplicate
names, English-key membership, apostrophe escaping and all eleven `%1$s`
placeholders were checked by source inspection. Existing English and translated
resources, baseline and lint rules are unchanged. The sorted path-and-SHA-256
inventory digest is
`11bcddf2e448e3fbb6be7198253700a7e457ec06a78f6515be4fb14e213262af`.
This is source review; AAPT/lint results and device presentation are separate.

## Limits

The new app fixture checks upgrade orchestration with Location data; the existing
fuller `LegacyV26ImportTest` and separate released Points fixture supply additional
coverage. Their executed results must be reported separately.

Replacing conflicting Location points is still explicitly unsupported in this
snapshot. The approved current-correction feature and the two pending Location gap
repairs are not completed by these compilation fixes. Full assembly, native/device
evidence, integration and release remain open.
