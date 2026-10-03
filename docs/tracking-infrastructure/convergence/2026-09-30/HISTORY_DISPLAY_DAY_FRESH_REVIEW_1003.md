# Displayed-day History: fresh source review, 2026-10-03

Current status: **IMPLEMENTED_UNVALIDATED — H1/H2/H3 closed by static source recheck; broader held History assembly remains pending**. Earlier findings and pins below preserve the review history.

Independent, review-only inspection of the exact packet reported at `5f6fc0e559`, against the inherited original user requests, follow-ups and approvals. No production edits, Git operations, recursive delegation, compilation, tests, SQL execution, schema checks, provider activation, device/UI or convergence gates were performed. Authored tests are source evidence only.

## Finding

**H1 — Ordinary Location withholding accepts an unqualified receipt as erase authority.**

`core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/LocationReadScope.kt:14-16` hides a saved point whenever any receipt with its sample ID carries an older epoch or erased admission ordinal. It does not establish that the receipt still matches the saved point, or has valid retained integrity. The `location_accepted_receipt` foreign key binds only the numeric ID (`LocationPurposeAuthority.kt:12-15`); blank older proof is explicitly supported (`:49-50`). A mismatched or blank historical receipt can therefore make an otherwise ordinary saved point disappear. This happens in both candidate discovery (`UnifiedHistoryCandidateQueries.kt:33`) and the saved-point reader (`LocationPurposeDao.kt:57`), before Kotlin qualification can preserve it.

The existing qualified reader checks `matchesCanonicalSample` and `hasRetainedIntegrity` before interpreting epoch/high-water authority (`RetainedLocationContextReader.kt:75-84`). The new ordinary history predicate needs an exact positive native binding before withholding; missing/invalid historical proof must retain the approved ordinary/unknown-origin behavior. Reuse the existing binding seam; no new proof or storage architecture is requested. Add a focused stale-epoch/high-water fixture whose receipt no longer matches its canonical point (and clarify blank older proof treatment), while retaining the current authentic debris and exact-current-import-copy assertions. The new blank-proof fixture only covers the current epoch (`RetainedLocationContextReaderTest.kt:108-118`); it does not exercise this branch.

## Source trace and selection findings

- Day discovery is finite: at most 101 probes per source, one real displayed day skipped per probe, UTC milliseconds unchanged, `ZoneId`/`atStartOfDay` supplies real DST bounds (`UnifiedHistoryRangeReader.kt:384-409`). Existing bounded merge/lookahead remains in use. Location/Wi-Fi/Cell use this path; Steps retains its structural count-domain day/zone reader (`:349-380`, `:543-574`). No count proration or source calendar rewrite was introduced.
- Radio candidates remain discovery, not admission. Current revision predicates feed the existing ordinary native/imported qualification and child completeness/checksum readers in the same Room transaction. The new `includeObservationOverlap=true` entry points use the new DAO predicates; ordinary reads explicitly delegate `false` (`RoomAmbientWifiRepository.kt:61-108`, Cell `:64-111`). Existing ordinary/export window predicates are unchanged.
- Each radio child is selected by its actual saved timestamp with half-open bounds; the original parent, revision, source day/zone, children and explicit gaps remain in `read`. Display getters filter children and do not manufacture measurements. Missing detail and gap-only payloads yield an unknown count (`UnifiedHistory.kt:59-74`; range reader `:503-541`). Ambient gap contracts require a positive interval, so this slice does not introduce a zero-duration ambient gap policy.
- Location's descriptive origin, retained floor, current import membership and sessionless organization are separate from enrichment authority. Qualified ambient points alone receive `QUALIFIED`; old or unverifiable saved points remain with `LEGACY_UNVERIFIABLE`, and proof lookup errors produce an explicit problem while retaining points (`UnifiedHistoryRangeReader.kt:452-500`). The exact current archive predicate is a membership/epoch join, not a new collection grant (`LocationReadScope.kt:6-10`). The inherited native receipt withholding issue is H1 above.
- Session/file/day point payloads carry the canonical positive `sampleId`, allowing sibling purposes to identify the same saved point without rewriting identity (`UnifiedHistory.kt:136-146`; native reader `:262-263`; range reader `:490-491`, `:699`). Imported point IDs are looked up in bounded 64-ID groups and excluded from unrelated legacy interval membership (`UnifiedHistoryNativeReader.kt:221-222`, `:251-254`). Genuine old local interval organization remains available; narrowed/hidden legacy interval points can appear in day history. This creates no retrospective native consent.
- Existing mixed organization/archive/DI drafts are not accepted by this review. Existing retrospective selectors and final UI consumers need their own coherent source selection. In particular, no production History UI currently consumes the new `BackgroundWifi`/`BackgroundCell` display getters; the packet honestly leaves polished History UI pending. The pending source-midnight digest/projector decision is absent.

No other concrete defect was found in the selected day-display hunks during this source review. H1 must be resolved before selecting the authority slice as closed.

## Authored test-source evidence

Fifteen new cases are correctly counted: three API presentation cases, six Location Room grouping/paging/bounds cases, four retained Location cases, one native radio Room case, and one imported radio Room case. The sixth test path changes only legacy fixture labeling/default plumbing. Tests have **not run**.

- The API cases check selected child timestamps/counts and `assertSame` original facts; they do not validate stored source-day ownership.
- The native composed Room case uses real retained grant/checksum fixtures, crosses display midnight within one valid saved source day, checks both sources' selected children, original stored records and unchanged ordinary DAO scope (`RoomAmbientRadioDetailCompletenessTest.kt:72-99`). It does not prove provider delivery or source-midnight partitioning.
- The imported composed Room case imports summary archives through the existing transfer to obtain actual receipts, adds exact-owner/checksum child fixtures, checks earlier-child selection, unchanged ordinary window scope, and a gap-only day. It is not a complete v2 codec import execution claim (`RetainedImportedAmbientRadioReaderTest.kt:127-181`).
- Location fixtures meaningfully cover UTC/New York grouping, a 23-hour Prague day, 251 sparse days with 37-row continuation, exact point identities, legacy/import separation, narrowed intervals and 4097-point overflow. The test named “hidden or narrowed” exercises narrowing, not hiding (`UnifiedHistoryDayGroupingRoomTest.kt:105-112`); this is a coverage limitation, not a second implemented behavior claim.
- The capture-fence case supplies valid ambient authority and an old capture marker; it proves the static shared-reader branch, not real capture writer/service lifetime (`RetainedLocationContextReaderTest.kt:148-162`).

## Exact frozen input pins

All 16 current file hashes matched the manifest during this review.

- Manifest SHA-256: `8d1440de8ae264ec5a7d3397e2f513f4228b3f852622a11a2fad231bde88001d`.
- Boundary SHA-256: `d3a49256b030935ed3ae487ea88753d3abc301ab4948de7749b6c069ff2b51fd`.

Full-file pins identify review input only; they authorize no unrelated held hunks.

| Path suffix (module-relative directory is the manifest's exact path) | SHA-256 |
|---|---|
| stats/api — UnifiedHistory.kt | `b3b19bf1d7c2e0866b90802ecc586acea74a2888449a728d07cc32cf386f4dc6` |
| core/base/dao — LocationReadScope.kt | `7a0baf6ef5a62d7dad73a4953988816c363b62b33dc5d5eb03248fa58eb2bdd0` |
| core/base/dao — LocationPurposeDao.kt | `aa419d78fa51ffe03ae241002e0f30ee7f06b5442f9f69604cd5a78e16971234` |
| core/base/dao — AmbientWifiFactDao.kt | `8926253310f1d73c24e66ddaca320b0f6ebf841ebb26093a17a44052471617d1` |
| core/base/dao — AmbientCellFactDao.kt | `a8646692413b25ca68fccfd817253ccc78967b22b6a69d68efbde19bbc35fe1d` |
| stats/data/repository — RoomAmbientWifiRepository.kt | `58fcc62be293a5eba3ed41d4039462891c6b8869d907db8fb123e03292d9c51f` |
| stats/data/repository — RoomAmbientCellRepository.kt | `84e2b415704cb2843ba99381ed12beee5533ad09f69c0bbfc3e5a577f5b8d2d7` |
| stats/data/repository — UnifiedHistoryCandidateQueries.kt | `917706a0e13c04c34bad3d57e2812fac211626ccd6e406b39a08e4e39ad0b0b9` |
| stats/data/repository — UnifiedHistoryRangeReader.kt | `a794734085a8a06ae4693592cdf6d13627a3bb4ef102468ddbb20bc05f505e7c` |
| stats/data/repository — UnifiedHistoryNativeReader.kt | `a4a1bebfa40a82b2b2c315b7aa563f0556c75cb3b2eea981aa662b978aa808eb` |
| stats/api test — UnifiedHistoryDayWindowTest.kt | `60631b5228c58052d0e6b0c1f8d0d0ff1ad10b79b54f77dc2b8563d502e6ea19` |
| stats/data test — UnifiedHistoryDayGroupingRoomTest.kt | `d0b5e420d0799b88b5da9846610bfeb41ca960f1957c81342400f5cf147b8b87` |
| stats/data test — UnifiedHistoryRangeReaderRoomTest.kt | `784d62f2664ce6bd4c7792d61e3945dc6a74110863d59c0f4beccafd99da5b53` |
| stats/data test — RetainedLocationContextReaderTest.kt | `38e5167cef46c2ca2d3bcb63ff94b336f17776aef22da8b0830db57efedeb010` |
| stats/data test — RoomAmbientRadioDetailCompletenessTest.kt | `78fa12632d2779455f57d63903a066405b5626230d572c1c90e2456d16caeb36` |
| stats/data test — RetainedImportedAmbientRadioReaderTest.kt | `cd0045b65316777017f39a620aec828488eb49c5401a15fe9fb127dc1bf788ac` |

## Evidence boundary

No full R11, six-source UI, all 25 prerequisite, migration/Hilt, device/battery, integration or release readiness is claimed. No tests pass by virtue of being authored. Final convergence and fresh review of its actual results remain required.

## H1 repair recheck, 2026-10-03

**Original H1 is closed by static source inspection. One new runtime-path finding H2 remains.** Earlier findings and initial pins above remain historical evidence. The exact initial packet is preserved under the `_PRE_H1_REPAIR_2026_10_03` manifest/boundary names, and its hashes still match the original pins.

The repaired packet contains 11 production and 6 test paths, with 18 authored/unexecuted scenarios. All 17 current hashes matched during this recheck. Current packet pins:

- Manifest: `e1567e1ddcdb49bb6a4922a6a8a3c07f6c2e72ee3615663647607f66199ba71d`.
- Source boundary: `b055cbb5f0b953da9e666813fb7209906e091d565e9fcdf60bfa2e7f55949b1e`.
- `LocationReadScope.kt`: `902d7f9e09347a442e2b5a3d22fbab495a99a07c436a76f9cf9be59af9976c04`.
- `LocationPurposeDao.kt`: `352765899bf69ad174c0b3459e3f62a4934a4d7bd214dbcbb2a4fd584268ef33`.
- `RetainedLocationContextReader.kt`: `0eca32cb39b6e19a842e102328048cf547ed514b646e3a4ba462092599263da0`.
- `UnifiedHistoryRangeReader.kt`: `765d45783344f9d12263e4015f522c7d22fef0f3e977c035edc733f66c5717a0`.
- `RetainedLocationContextReaderTest.kt`: `62a9e476c619482f821ea8d60d6aacc43784c787f333e4b7d53bbbaef8ef2793`.
- All other 12 source/test pins remain the initial values in the table above.

`HISTORY_RETAINED` now performs only floor/settlement discovery (`LocationReadScope.kt:12-16`). The projection's `has_current_import_copy` column matches `LocationHistoryDayRow.hasCurrentImportCopy` (`LocationPurposeDao.kt:31`, `:54`). The new erased-native set is filled only after retained receipt integrity, exact canonical measurement binding and the existing native writer identity succeed (`RetainedLocationContextReader.kt:75-90`). Blank, corrupt and mismatched receipts cannot create that set. The day reader preserves qualified ambient and exact current import membership before applying native erase (`UnifiedHistoryRangeReader.kt:481-490`). Existing map qualification, public map result contracts, ordinary/export predicates and provider demand are unchanged. The appended internal snapshot field has a default, preserving existing constructor callers.

Three focused real Room source cases now change an epoch/high-water boundary after installing a blank receipt, corrupt retained checksum or different canonical measurement (`RetainedLocationContextReaderTest.kt:121-173`). They assert both ordinary saved History and the empty erase set. Existing authentic erase/current import and capture-fence/ambient sibling assertions remain (`:176-217`). This is meaningful authored coverage, not an executed pass.

### H2 — Optional context dependency limits bypass authenticated native erase

`RetainedLocationContextReader.qualifyInTransaction` loads purpose/context dependencies and can return `limited()` before authenticating native erase. In particular, more than 100 distinct run IDs returns at `:51-52`; purpose, manifest/source and fence budgets also return at `:39-40`, `:54-56`, `:64`. `limited()`/`unavailable()` returns an empty erased-native set (`:144-151`). The day reader then preserves every point whose ID is absent from that empty set (`UnifiedHistoryRangeReader.kt:488-490`). Thus an old-epoch/high-water point with a genuinely valid receipt/canonical binding can become visible because optional enrichment dependencies exceed their limit.

This concerns already available positive erase evidence, rather than missing/corrupt historical proof. The bounded receipt/canonical/state checks needed for erase do not require manifests, consent joins or map-context clocks. The smallest repair is to compute the authenticated erased set before optional context dependency work and carry it through context-limit outcomes. Preserve the approved current imported-copy override, blank/corrupt/mismatched ordinary data and cancellation/error distinctions. Add one bounded fixture with valid erased native receipts and a context run dependency limit, asserting the limit remains explicit while the erased saved points stay withheld. No new storage or authority architecture is requested.

No other new concrete defect was found in this repair's selected source hunks. Full untracked R11 readers, organization/UI and held source export work remain outside acceptance. No gates or production changes were performed by the reviewer.

## Frozen H2 recheck, 2026-10-03

**H1 and H2 are closed statically. H3 below remains in composed History error reporting.** This closure concerns the exact source below, not a test pass or whole-file approval for held R11 readers.

Exact hashes checked and matched:

- `HISTORY_DISPLAY_DAY_H2_FILES.json`: `34ddb8aa7c6fc38c5d0ac1538f71d18aa55812003dbe760048845c7ec92482b8`.
- Latest `HISTORY_DISPLAY_DAY_FILES.json`: `a66ef4f0386619f714b74c7631592965e399885dad34dd06fe96f3ad155330f8`.
- Latest source boundary: `92f3e1ebba3671ea8ce2fc2a9befaf8c1c8cb239e26f336c30a8e956ddc6e833`.
- `RetainedLocationContextReader.kt`: `935fdf4642711618e20e4100ba82112d18a27596424b5d30ad0a5d9903dedd51`.
- `RetainedLocationContextReaderTest.kt`: `6f0beb77952d85e7b1a846f6b207c70ab88f15fd30476d8d5f667cda6e7071f0`.
- All 17 paths in the latest full manifest match; the other 15 file pins remain the H1 packet values above.

The reader now computes exact authenticated sample IDs and erased-native IDs immediately after bounded receipt uniqueness/state/sample reads (`RetainedLocationContextReader.kt:29-46`). It uses the existing retained checksum, exact canonical binding and exact native writer checks, not a new proof. Missing/blank/corrupt/mismatched proof cannot create erase authority. Optional stamp, purpose, run, manifest/source and fence failures preserve the set (`:48-78`, `:148-158`); cancellation is rethrown (`:146-147`). Expected purpose qualification remains otherwise unchanged, and erased receipt IDs never become map context (`:87-99`). Unbounded/duplicate input or unavailable dataset state still returns before authentication; the day reader supplies unique bounded candidates. Initial sample/state read errors cannot identify positive exact authority and retain the existing outer failure behavior.

The four direct Room methods (`RetainedLocationContextReaderTest.kt:92-145`) exercise the actual optional run guard with an authentic high-water erased receipt, authentic old dataset receipt, blank receipt, and corrupt then canonical-mismatched receipt. Each asserts the erase set and explicit dependency gap; authentic cases also assert saved rows physically remain. Their shared helper (`:316-321`) installs 101 distinct capture metadata run markers under the existing exact sample foreign key. It does not invent capture grants or claim real provider/session lifetime. These four methods and helper call no held range reader or `rangeReaderForRoomTest`; they are a coherent separately selectable core source fixture slice.

Two further composed cases (`:147-184`) use the held History backend to check that an authentic erased point stays hidden, exact current imported membership independently survives with an explicit limit, and a blank-proof saved point remains ordinary with that same limit. These methods must remain held with the day backend. The earlier seven day cases remain held as well. The existing `installPoint`/sample/policy/radio fixture is unchanged. New scenario count is six methods, not seven (one corrupt/mismatch method covers two variations); full packet count is 24 authored/unexecuted scenarios.

Existing day selection still applies qualified-ambient/current-import overrides first (`UnifiedHistoryRangeReader.kt:481-490`); the SQL discovery no longer performs receipt-only erase. The authentic capture-fence/ambient sibling case remains unchanged. No source-midnight digest, schema, exporter predicate, public map contract, source demand or Location replacement work was included.

### H3 — Caught optional read failures lose the History problem

The H2 repair catches optional SQLite and runtime failures and returns `LOCATION_AUTHORITY_UNAVAILABLE` while retaining the erase set (`RetainedLocationContextReader.kt:148-151`). This correctly protects erased data. However, the composed day reader maps only `LOCATION_DEPENDENCY_LIMIT` from `qualified.gaps` (`UnifiedHistoryRangeReader.kt:503-505`), and the native compatibility reader also maps only that limit (`UnifiedHistoryNativeReader.kt:274-276`). The exceptions no longer reach their existing storage/integrity catches. A surviving ordinary or independently imported point can therefore appear with no explicit read failure even though an optional database/shape read actually failed.

The approved behavior says proof lookup failures preserve points **and produce a problem**. Preserve the authenticated erase set while exposing an internal optional read failure distinction to these History consumers; expected absent/old proof need not become an error. No new public map format or storage is required. The four direct and two composed H2 methods test limits, not the newly caught error branches; a focused authored failure-path assertion is still needed. This is a composed-reader follow-up, not a reason to select whole held R11 files with the core H2 methods.

No other concrete defect was found in the frozen H2 source. No production changes, Git or deferred gates were performed by this reviewer. The core H2 selection must remain exactly the packet's reader hunks plus four direct methods/shared helper; the nine composed day methods require the separately held History assembly. No full R11, assembly, device, test-pass or integration readiness claim is made.

## Final H3 recheck and direct core selection, 2026-10-03

**H3 is closed statically. No concrete defect remains in the selected H1/H2/H3 source repairs.** This is implementation source acceptance only. The held day/native/backend/UI files, all deferred gates and final integration remain pending.

Frozen H3 input and direct selection pins checked and matched:

| Input | SHA-256 |
|---|---|
| `HISTORY_DISPLAY_DAY_H3_FILES.json` | `92b7c4781d1c96cf595e6499eeaf9ffc64502b9b17b43784e32ca44038e6a70c` |
| Latest full day manifest | `bc21fee2e5143c73aa0c26b5b65f9e25e4675f9a88f89debc89174b5d3024f7b` |
| Latest full day source boundary | `f675ef228535ce3f4d3afc9ffa844128d741a23184f86587dc7d4da29deff351` |
| `RetainedLocationContextReader.kt` | `7b26cecd95d2ca3ee9de099f7cc6b6904d5527f49d0c38cc797ffc8b7278d3a0` |
| Full mixed `RetainedLocationContextReaderTest.kt` | `92c2871a7fd2cbb1152f2d2f70395a6943b8ffe4c4b7c912c90920f710c458d3` |
| `UnifiedHistoryRangeReader.kt` | `e9fc318dc9abd7105e7cc798c06f2a9a284453aa7cf0bfec8b654a9d5fa0676f` |
| `UnifiedHistoryNativeReader.kt` | `be66c6c8a3e7d773499879383f6459cf04727ebc8dbe1362c70278553e30e1ab` |
| Full mixed `UnifiedHistoryRangeReaderRoomTest.kt` | `d15464bfad155242ceee3e427b229976c53311a315cddefd8e71ee1ab11e5a5c` |
| Desired `RetainedLocationContextReaderTest_DIRECT_H2_H3.kt.txt` | `7c352e70431fb37d85277dea28940db8c0e82aa672be740450889c99d5c203b3` |
| Root-provided `RetainedLocationContextReaderTest_HEAD_AT_1E58017.kt.txt` | `818ebd6917263d2712fdfb22eca52b7621e74b9b5e6e6f99298d82408c20e8fb` |
| Root-provided `2026-10-03/RETAINED_LOCATION_CORE_SELECTION.patch` | `5243d3585cfc7d68a731dab56c41debde71db4f54ba4f85f89f8915d8899c2d7` |

All 17 latest full-manifest hashes match, for 11 production and 6 test paths with 29 authored/unexecuted scenarios. Full-file pins never grant permission to select unrelated held hunks. The baseline test snapshot is parent-provided read-only HEAD at `1e58017f39`; the reviewer performed no Git read or mutation.

### Actual error propagation

The internal snapshot appends default-null `historyProblem: HistoryProblemReason?` (`RetainedLocationContextReader.kt:165-170`). It uses the existing History enum and no schema/public map contract. Optional SQLite failure alone sets `STORAGE_UNAVAILABLE`, optional runtime/shape failure sets `INTEGRITY_UNAVAILABLE`, and both preserve the already authenticated erased-native IDs (`:149-162`). Cancellation is still caught first and rethrown (`:147-148`). Expected absent authority or old/blank proof continues to carry its normal context gap with a null History failure. The exact receipt/canonical/native-writer authentication remains before optional dependency loading (`:30-47`); no new proof is created.

Both real composed callers now consume the internal field: the day reader uses the existing caught `qualificationProblem` first, then `qualified.historyProblem` (`UnifiedHistoryRangeReader.kt:503-505`); native compatibility uses existing `proofProblem`, then `qualified?.historyProblem`, then its dependency-limit mapping (`UnifiedHistoryNativeReader.kt:274-276`). Saved ordinary points remain available with honest coverage, authentic erased native points remain withheld, and exact current imported membership still independently wins over the native erase set (`UnifiedHistoryRangeReader.kt:488-497`). Genuine missing proof therefore stays an ordinary unknown-coverage state rather than being fabricated as a storage error.

### Exact direct-test selection

The desired direct snapshot contains the six original test methods plus **seven added direct methods**: four H2 cases at `:92-145` and three H3 cases at `:147-182`, with `installOptionalRunOverflow` at `:184-191`. All seven methods and the helper were read in full. There are no `rangeReaderForRoomTest`, `UnifiedHistoryRangeReader`, `HistoryRangeRequest` or composed day method references. The existing test database, source fixture and source-owned qualifier are the only exercised seams.

Against the pinned HEAD snapshot, removing precisely the one added block reproduces the original six methods and `installPoint`/sample/policy/radio fixture exactly after LF normalization; only one extra final newline remains. The parent-provided selection patch contains exactly those seven methods/helper and that final newline. No held R11 source or test fixture is pulled into this selection.

The direct H3 storage case drops only the optional policy table after exact receipt/sample/state setup, asserts the real qualifier's storage outcome, retained erase ID and still physically saved point. The shape case makes an existing purpose's interval invalid so the actual validating Room entity constructor rejects it, then asserts integrity outcome, erase ID and saved point. The third case uses an older blank proof and asserts a normal provenance gap with `historyProblem == null`. These distinguish actual failed reads from unavailable old proof meaningfully; they are authored sources, not executed evidence.

### Held composed cases

The additional day case (`RetainedLocationContextReaderTest.kt:129-148`) uses actual Room membership and a real optional policy query failure, then checks that the exact current imported copy remains with a storage problem. It is not in the direct selection. The additional native case (`UnifiedHistoryRangeReaderRoomTest.kt:115-127`) uses real Room legacy data and a **mocked source qualifier result** for both existing error reasons; it proves caller propagation in source, not a real native optional query failure. It also remains held. Earlier composed H1/H2 methods remain held with their History assembly.

The separately selectable core packet is therefore the scoped `RetainedLocationContextReader` H1/H2/H3 repair and exactly the desired direct test snapshot/patch. Actual day/native caller closure is recorded above, but their full untracked/mixed R11 files are **not approved wholesale**. No test pass, compilation/Room/Hilt, schema, provider/device, whole feature, all-25-prerequisite or integration readiness claim is made. No further review-driven production change is required for this narrow repair.
