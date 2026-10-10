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

## Compose resource observation repair

The independent reviewer accepted the three exact production diffs:
`TrackerDashboardSessionCards`, `CalendarContent` and `MeasuredHistoryContent`.
The session accessibility label resolves the same resource IDs and arguments
through `stringResource` during composition, retaining order and separator before
assignment to the semantics lambda. Four locale reads now use
`LocalConfiguration.current.locales[0]`, retaining the same formatters, first
configured locale, labels, layout, navigation and callbacks. This repairs the
seven blocking Compose resource/configuration lint findings without changing copy
or suppressing a rule. The existing Arabic Calendar fixture now also provides its
matching configuration. A focused test changes the displayed locale after
composition and checks the month label changes while the selected day remains.
The independent reviewer accepted both test changes. Actual compilation, tests
and lint are required separately.

## Prepared-start architecture fixture repair

The executed rerun exposed two more stale rollback assertions. The service already
uses an exact token/command/start ID and synchronized ownership checks to avoid
retiring a successor or a start that owns physical providers. The architecture
fixture now checks that exact finally call and the bounded rollback method body:
ownership guards, coupled session/flight clearing, and preservation of independent
background foreground hosting. No service behavior was changed. The independent
reviewer accepted the exact test SHA-256
`198a93e10f311c3ccd2470d0e47a6deca7ac4546a845299ebf3a9e34918c414a`.
The existing seven real-service cancellation/successor cases remain blocked by
package access; this architecture fixture needs its own executed rerun.

## Repairs from the executed storage rerun

Independent source review accepted these existing-contract corrections; executed
results must come from a subsequent run:

- Source-policy reconciliation refreshes the current active snapshot under its
  existing mutation lock before reconciling an unreconciled observation. A delayed
  older Room emission no longer rolls provider reconciliation backward. The
  already-reconciled publication path remains independent of the compatibility
  mirror. Reviewed SHA-256:
  `06ef3387c9abeb761156386c220f909903566274ac6feb557885625f960b0670`.
- The raw lifecycle mapper now carries all 21 columns already projected by its
  DAO, including the existing nullable caller reference. Existing strict SQLite
  types and nonblank reference checks remain; real DAO tests retain nonnull/BLOB
  coverage and add null, malformed signatures, INTEGER and blank reference cases.
  Reviewed mapper SHA-256:
  `ed46cf4c24a8325e1b6aaa6ff6ffbb302c2003cfeaab697e9c1b523a76cb4df6`.
- Released-schema fixtures accept a missing index array as empty. The actual
  released version-12 `network_operator` table has no index array. Present arrays,
  source schema/hash checks and migration/preservation assertions remain intact.
- Activity's changed-cutoff negative fixture updates its original Stop timestamp,
  deadline and checksum consistently. Cell fixtures acknowledge a retired owner
  at its actual retirement, or seed a genuinely continuing owner with an open
  provider lifetime before a later capture. Positive, corruption and rejection
  assertions are retained; no production interval checks are relaxed.
- The wrong-purpose Cell carrier fixture targets legacy v5, whose capture-only
  semantics it asserts. The current v6 independent background behavior remains.
  Radio export malformed-owner fixtures use distinct source-prefixed run IDs.

- Three radio DAO queries now use the existing Wi-Fi/Cell source constants (5/6),
  correcting the stale Pressure/Wi-Fi pair (4/5). Their other predicates remain.
  The real Room test covers both radios, Pressure exclusion and the existing
  active-owner, source identity, current revision and no-capture guards. The
  independent reviewer accepted exact DAO SHA-256
  `efa7b13b5da0fcd3ec8241035e68826c7753bb483e8476584118592498294e37`
  and test SHA-256
  `b43d0b0cd6d45a268edae58528d8fc6ac755a01be97cd3e846eee4b06a595345`.
- The partial schema fixture applies exactly four relevant v29 additions before
  comparing its historical helpers with fresh Room. History invalidation awaits
  real Room work with the same five-second deadline on a real-clock dispatcher.
  Only the deliberately maximal 65,536-window real-Room case uses a three-minute
  test-body deadline, retaining full boundary, overflow and no-sink assertions.
  The independent reviewer accepted these exact test changes.

The generated Room schema is unchanged by these mapper/query repairs. All these
source acceptances still require executed results from the subsequent rerun.

## Retention boundary repair from rerun four

The released-data retention fixture reached its real assertion after the missing
index-array fixture repair. Its cutoff had equalled the imported session end,
although the existing segment deletion rule is strictly before the cutoff. The
test-only cutoff is advanced by one millisecond; all preservation and deletion
assertions remain.

Tracing that failure also found a production mismatch: linked annotation overrides
expired at or before the cutoff, while their recording segment survived at the
exact cutoff. The hidden override then hid the fallback session row. The DAO now
uses the existing strict segment cutoff for linked overrides. Free retrospective
annotations retain their existing rule. A real Room regression exercises the
retention transaction and checks visible boundary metadata and deletion of the
earlier sibling. The independent reviewer accepted production SHA-256
`24c44a06e5ee782b8ab980a67d1f1395c0bfb2ed663073e84d9913847ed36777`
and test SHA-256
`de61443b40d3440d704c64fdf02b442ecd781572e8ab4db59e0769efe473d904`.
Executed results for this repair still require the next rerun.

## Remaining host-fixture repairs from rerun four

The independent reviewer accepted the following changes with existing assertion
bodies and time limits retained:

- The v28 radio migration fixture supplies the missing `session_segment` parent
  that the real migration extends. Two file-backed test names are shorter to avoid
  Windows native SQLite path limits. One observed containment path's journal
  reached 260 characters; the full-clear XML reports journal-opening failure but
  omits its path, so that case's path-limit explanation remains provisional.
- The released retention fixture cutoff is one millisecond after the session end.
- The notification dialog test configures the actual host's Czech locale, retaining
  displayed text and both action assertions. Its prior failure alone did not prove
  a production dialog bug.
- The codec's expression-bodied public test explicitly returns `Unit`, instead of
  exposing its final `assertThrows` result's internal exception type.
- Statistics assertions use the available `hasAnyAncestor` matcher and retain the
  member `assertDoesNotExist` calls without the invalid extension import.
- Manual Start's effect fixture drains the host queue after four state mutations,
  before asserting collector disposal. Original generation, serial retry,
  cancellation and screen-scope assertions remain intact.

Executed dependency insight resolves the Tracker host-test Compose UI libraries
to 1.11.4 under BOM 2026.06.01. Source explanations based on other cached patch
versions are not claimed as an exact-library reproduction. The next executed test
results remain the acceptance evidence for these fixture repairs.

## Export lint repairs from rerun four

The import worker's `runAdmittedImport` leading indentation is normalized; its
whitespace-insensitive diff is empty. The independent reviewer confirmed unchanged
notification, dispatch, cancellation, catch ordering and result behavior.

All 29 missing existing export string keys are translated in 27 existing regional
resource folders (780 new strings). Czech's three existing format labels are
preserved. Only additive `tracking_transfer_completion.xml` files are included;
English, old translations, baselines and lint policy are unchanged. Independent
inspection confirms exact names, locale-wide uniqueness, valid XML, the original
`%s` placeholder and Android apostrophe escaping. The exact generated inventory
SHA-256 is `b4a9afee1e61eacbcf8d70a569d77f0106e589440ac35af1864255747a3b22a7`;
the reviewer's sorted path/hash digest is
`c5aa1924957d9f5f23fc13f9f9feb8ed5d17080d18648bab5a3f0b78fce5d9c7`.
Spot-checking meaning is source review, not human-language or device validation.
The subsequent frozen gate batch supplies AAPT and lint evidence.

## Limits

The new app fixture checks upgrade orchestration with Location data; the existing
fuller `LegacyV26ImportTest` and separate released Points fixture supply additional
coverage. Their executed results must be reported separately.

Replacing conflicting Location points is still explicitly unsupported in this
snapshot. The approved current-correction feature and the two pending Location gap
repairs are not completed by these compilation fixes. Full assembly, native/device
evidence, integration and release remain open.
