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

## Repairs from the fifth frozen batch

The independent reviewer accepted all nine files in this slice:

- The radio JSON decoder now declares its Gson result as `T`. Without that
  annotation Kotlin inferred `Void`, so valid Wi-Fi and Cell files failed before
  import. Lexical, size, trailing-content, validation and import authority checks
  are unchanged. Production SHA-256:
  `66339722214d67b3e6f47b50b91cafb551b8d4ec4af8bb2605e6a5d3a6f7924b`.
- Two codec tests explicitly return `Unit`, retaining their exception assertions.
  Four invalid Compose imports are removed; all 23 receiver assertions remain.
- The JSON export test retains its mid-page authority-change rejection, then
  checks that a fresh retry includes the original saved background point. This
  matches the approved rule that stopping collection keeps previously recorded
  data. The neighboring retention-preparation rejection test is unchanged.
- One startup generation fixture gives its real Room query context and isolated
  application scope the same test scheduler, and drains initial setup before
  resetting counters. All retry, exact generation, zero pre-Ready effects and
  single rearm assertions remain. The earlier intermittent failure is consistent
  with uncontrolled real/virtual scheduling; its exact interleaving was not logged.
  Test SHA-256:
  `a84ff144c938150f616a764eba7166f4aa1d96461b4d05ce7145a3a666b53df7`.
- The manual transfer fixture uses an unregistered test MIME type. Android had
  appended `.bin` for its former `application/octet-stream`, while its assertions
  read `.dat`. Filenames, write content and all concurrent-directory and cleanup
  assertions remain. No production cleanup change was needed. Test SHA-256:
  `21a48d361ba8d2339c1183a28be58a0e96395d2bb667c190d311ac74606348d2`.

The fifth batch used committed input `074782e1ae94c077918f47c86778972fd605fc40`
without concurrent edits. It passed all 2062 core/base tests and 142 Tracker tests,
including the released observation import and reviewed retention boundary. All
four requested release lint tasks passed. Preferences had one failure, Export had
eight failures, and Statistics test compilation failed; the repairs above require
their own frozen rerun. App assembly and aggregate schema drift remain blocked by
the unavailable pinned diagnostics package. This source review does not close
those gates, the full assembly or device evidence.

## Repairs following the sixth batch

Strict Gson trailing-content inspection throws `MalformedJsonException`, which
extends `IOException` and bypassed the decoder's existing runtime-format catch.
The decoder now wraps that exact parse exception in the existing format exception.
Cancellation and ordinary `peek` I/O retain their previous behavior. This does
not redesign Gson's separate decode-time wrapping of transport failures.

The isolated bootstrap fixture uses `StandardTestDispatcher` instead of its new
Unconfined dispatcher. The sixth-batch failure was a continuation-release cast,
not a policy assertion. Shared Room/test scheduler ownership and all generation,
zero-effect, retry, timing and rearm assertions remain. This minimal fixture
candidate needs execution before its scheduling explanation is considered proven.

Statistics fixture repairs reveal the existing horizontally scrollable History
action and lazy Loading/Retry controls before interaction. The long-history case
still asserts the saved Steps card and count before scrolling. Partial Steps uses
the current `Incomplete` label, scoped to its summary, and explicitly rejects an
invented zero. The measured-history Retry fixture keeps its real Pager and source,
reveals the Retry button, waits for host idle, and retains its bounded second-load,
healthy sibling and eventual Retry-absence assertions. No layout, production
pager, duration or source acceptance rule is changed. The separate date-picker
day-selector failure is addressed by the test-only selector repair below.

The sixth batch's 774 Export tests have one failure and one existing symlink
assumption skip. Preferences has one failure among 492 tests; Statistics has eight
among 580. Export release lint passes. This record does not convert these failures
into passing evidence; the repaired slice requires the next committed rerun.

The focused seventh batch passed all 81 selected tests: authoritative preferences
18, Statistics screen 36, measured History 20 and radio codec 7. It used frozen
input `d30badadcb95ee71515fc8603a78318c064b0ce8`, exit 0, 2m9s, 262 tasks.
This is subset evidence; it does not replace the complete affected suites or the
repository gates. The previous date-picker failure was outside that run.

The pinned Material3 1.4.0 AAR exposes full formatted dates on each clickable day
surface, while clearing the numeric child text's semantics. The date-picker test
now selects the actual full-date semantics for June 17 and 18 using the default
formatter and its current configuration locale. Its actual calendar clicks,
confirmation, local-range values and zero-clear assertion remain. No dialog
layout, screen-size qualifier or production date calculation is changed. This
selector repair still needs its executed result.

## Existing exact-version dependency mirror

Tracker's existing `traceboxLocalRepository` property is a local validation seam
restricted to `io.github.tracebox` and forbidden in CI. The local SDK workspace
contains all 30 reviewed alpha.7 AAR/module/POM artifacts. The isolated validation
mirror rejects checksum mismatches and restores only POM CRLF bytes to LF, after
which each file equals its already checked-in reviewed SHA-256. It introduces no
alpha.8 substitution, dependency change, repository policy, verification weakening
or SDK edit. The source archive and local tag agree on alpha.7 commit
`b49e8fda94e0bd65859eb080cacd624d9b72a7f5`.

This supplies a legitimate input for the next local host batch. It does not prove
authenticated GitHub package consumption or satisfy a clean external CI run.

## Limits

The new app fixture checks upgrade orchestration with Location data; the existing
fuller `LegacyV26ImportTest` and separate released Points fixture supply additional
coverage. Their executed results must be reported separately.

Replacing conflicting Location points is still explicitly unsupported in this
snapshot. The approved current-correction feature and the two pending Location gap
repairs are not completed by these compilation fixes. Full assembly, native/device
evidence, integration and release remain open.

## Batch-nine compiler completion review

The fresh independent reviewer accepted the 14-file engine/core repair slice by
static inspection, inventory digest
`4fa13999c0a53a2b4cebbcc3641d12d096241bcb02ed88281c9de9197491d8ca`.
The extracted internal Cell conversion preserves the original codec mapping and
observation order, and is shared with the already-written ambient qualifier.
Its regression checks two distinct observations and v5/v6 canonical bytes,
checksum and decode. The Location candidate is module-internal; only the
already-used Location payload cap is exposed to its coordinator. Radio caps
remain 64 and 256. Missing child evidence remains an explicit rejection.
Notification actions reuse the existing localized End session/Turn off tracking
resources and retain their intents. Nullable locals and the explicit Steps
transaction result type preserve existing guards and cancellation behavior.
This is NOT_RUN evidence until the affected rerun executes.

Frozen batch nine executed all 580 Statistics UI tests successfully, including
the previously repaired calendar selector. That executed result supersedes the
earlier test-source-only calendar note. It does not prove native UI behavior.
