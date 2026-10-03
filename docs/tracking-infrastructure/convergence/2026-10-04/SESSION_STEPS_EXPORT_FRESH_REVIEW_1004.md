# Independent exact Steps prerequisite review — 2026-10-04

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Review-only static inspection by
`/root/fresh_bundle_context_review_0930`, using the original user requests, follow-ups and approved
decisions. No production edits, Git operations, tests, compilation, schema generation, gates,
provider activation, device work or delegation were performed. All 25 assembly chains remain open.

## Initial finding

**S1 — OPEN: an explicitly selected native group can disappear into partial success.**
`stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/PortableStepsRoomReader.kt`
passes the exact request's original logical groups into the common native assembler with
`request = null` (189–210), but its candidate filter (258–269) can drop selected groups without
Steps capture/covered facts. `buildEntry` also returns null for wholly deleted groups (915–919),
groups entirely before the retention floor (927–929), and groups without a covered fact (969–973).
The entry loop simply omits those nulls (274–289). A second valid chosen native/imported entry can
therefore produce `Exported` although the exact request's first chosen group has no exported
payload. Final typed-content equality repeats the omission and does not detect it.

The smallest repair is to require every explicitly requested native logical identity to produce
one complete entry, or refuse the entire exact request. Preserve standalone date selection's
filtering. Meaningful source cases should include a valid sibling plus a wholly expired group,
a group without a covered product, and an authenticated wholly deleted group. The author accepted
this finding and is preparing a repair; this initial packet is not accepted for source selection.

## Other inspected seams

- Exact native seeds are loaded by original IDs, reciprocally checked against their original run
  and segment, then expanded through stable bounded original logical-run pages. Missing/unmapped
  seeds refuse before sink I/O. No exact-request date fallback exists.
- Imported IDs use the existing retained source reader in 32-ID batches, require exact returned
  membership and complete original portable entries, and retain original IDs, covered zero,
  counts, gaps, manifests and scopes. Per-entry integrity, epoch, deletion and retention failures
  refuse the exact source request. Existing finite snapshot/dependency budgets remain in use.
- The exact V2 path uses the existing strict `PortableStepsEntryV1` binding overload in
  `core/base/.../database/ImportedAmbientStepsCountDomainLineage.kt:166–184`. That branch reads
  bindings, graph and receipts, and authenticates them without the older retained-entry overload's
  reconciliation writes. Existing binding/graph limits apply. The existing native owner-lineage
  revision-1 capability limit is reported honestly; V1 remains available rather than inventing a
  V2 graph. This does not establish native V2 availability on devices.
- V1/V2 caller adapters copy requested ID lists, read a Room snapshot on the injected IO
  dispatcher, leave it before sink calls, and reread the same original request afterward. Full
  typed products (and V2 graphs) must remain equal; original epoch and retention settlement are
  fenced. Unrelated Activity/import/evidence revisions do not invalidate unchanged selected data.
  Cancellation escapes; failed rechecks require the caller to discard already staged bytes.
- Existing Hilt bindings and API/module dependencies remain unchanged. The three actual explicit
  test fake implementers add the required overload and reject accidental exact-request use in
  their existing date/import flows. No other implementer was found after inspecting production
  bindings and real callers.
- Reviewed all 23 added Room method sources and their actual fixture/DAO signatures: eleven native
  and twelve imported, including original replacement membership, missing members, epoch/content
  mutation, retention, independent writes, V1/V2 capability, saved graph/revision preservation,
  33-entry batch traversal and cancellation. These are authored coverage, **not passing evidence**.

## Initial exact pins

Independently hashed the manifest, patch, boundary, all nine actual inputs and the two selected
mixed-file after snapshots. All matched their initial manifest values.

- Manifest: `d57795cdea2b95df024ed4ad99c8a0434955799f21aca90a4afff8e9bb78ad79`
- Selection patch: `25d97d9c40d1fc24761cfeed4eb8727546dd4085f9be448c3bd532cc17ef0cdb`
- Boundary: `be04cb2e784f4a5736e1ff59ad6afcfd924b79cdfc88a44b34895cf46e9c861e`

| Selected path | Selected after SHA-256 |
| --- | --- |
| `stats/api/.../repository/StepsPortableTransfer.kt` | `f8dee6c477a7f357bb62cb37e36f8753528b51bd020299360fc8f9ceb2878dcc` |
| `stats/api/.../repository/StepsPortableTransferV2.kt` | `0bd5fc9e02d400b0ac9d139702121d57cb8e433715b2d50acd2eba33cd4ea035` |
| `stats/data/.../repository/PortableStepsRoomReader.kt` | `d375963b849a9971f1a9817f18e969c4ef241f1acc2983149abe16b2e30eac6d` |
| `stats/data/.../repository/ImportedStepsProductReader.kt` | `f9b303da1f2bcda73088c061072ecb6b0ebac3cff1a298bb534113c89325c705` |
| `stats/data/.../repository/RoomExportPortableSteps.kt` | `024a59630f36c459524fd47c0bfb08a5c3e14f6d3071e06d2cbd311382bccf52` |
| `stats/data/src/test/.../RoomExportPortableStepsTest.kt` | `f3ffbf363cb2afba7fed7bbdd0a65e6df0685cff3d49adbedced97ae8dcc3dd7` |
| `stats/data/src/test/.../ImportedStepsProductRoomTest.kt` | `fa25d74c98bc01ec759bb16fb4224d116468f8b24444fcb3a2dc6b449e7e7e42` |
| `feature/import-export/src/test/.../PortableStepsExporterTest.kt` | `92f7742f5598916841e8345855882ffb30beb9af817a11d12c77fa08b07f66a0` |
| `tracker/engine/src/test/.../RoomImportPortableStepsTest.kt` | `7a1d4cdeb6ecbeab3ff598d592499b94670a45446ac6fd31ab3236ade20d3d1d` |

The mixed reader's actual working hash was
`0cdd4066e187e9e0e037c1ba72968bb782b7d4d11aefc5cac91bd8313b34fda8`; the mixed imported
test's actual hash was `f6866fb15b4b8bd642c4262feb3adcb975921751e77d4d14803ef148fd4fe817`.
Their selected snapshots exclude the held history List overload, original-envelope presentation
change and leading held R11/original-envelope cases. Those unrelated drafts are not accepted.

This remains an exact-record backend prerequisite. The shared session surface, retrospective
measurement selection, bundle writer callers and full data/UI/export/device chain remain pending.
The newly approved measured-date export rule and complete-session switch are separate caller/date
selection work; this whole-original-record overload does not implement or supersede them.

## S1 repair recheck — 2026-10-04

**S1 CLOSED by static source review.** Exact mode now requires the complete candidate logical
identity set to equal the original selected logical set (`PortableStepsRoomReader.kt:268–270`),
then refuses any selected group for which the common builder returns null (292–294). Both exact
V1 and V2 use that same guarded assembler. A missing-capture, erased, wholly expired or
nonexportable selected native group can no longer disappear while a sibling makes the export
successful. The non-null standalone date request keeps its prior omission/date behavior; no
new reader, store, format, authorization or fallback was added.

Read all four new actual Room method sources and their existing fixtures: lines 1782, 1798,
1810 and 1823 create a valid selected sibling alongside, respectively, a wholly expired native
group, a group with no saved payload, a group with an authenticated whole-run erase fence, and
a group with removed capture membership. Each requires refusal before sink output. The expiry
case additionally requires ordinary date export of the retained sibling to remain available.
These use real source rows/DAOs, the existing complete run/manifest fixture and the existing
`createLogicalServiceRun` fence; no fake completed export or altered production budget is used.
The final packet has **27 authored, unexecuted Room cases** (fifteen native, twelve imported),
plus the three existing compatibility fake overloads.

Independently rehashed all nine actual current paths and both selected mixed-file after snapshots.
Every value matches the repaired manifest. Only the following selected/actual after pins changed;
the other seven paths and held-hunk exclusions remain exactly as recorded above:

| Input | Final selected/actual SHA-256 |
| --- | --- |
| `PortableStepsRoomReader.kt` | `f6ea1ec1081c63692b3f387964a66b7f3a0a061a5b76574a5122ae1f0cadde3b` |
| `RoomExportPortableStepsTest.kt` | `d2dfa8a48ce0c29e5d528bd8ccdf234759ac389943fb7ea67da4a150e5ae68eb` |

- Final manifest: `ccdcceeca19db048a7579a6f30d42537b093671feb4bddb931507a7d5266173c`
- Final selection patch: `cf4ec478b56fa62b64790e7fa14734052dc3b351d2dd4ebdac6a415a75bca683`
- Final boundary: `8bd50edf362b35acf5d5e97fd3d85c5ad7f86194aeb138b1574c9776406f1303`

The raw parent-supplied immutable reader baseline snapshot hashes to
`bdc195c313dc59298f9e428968dfe162996047906e1310c89d810ed846cb4ac0`.
The raw parent-supplied native test baseline hashes to
`2d4a838eaa72c7bcec097da08cb9319b7c738c0dd7b53a82d1340866ae5e9097`, correcting the initial
manifest's canonical-byte before pin. The repaired selection includes only the already reviewed
exact-source changes and four S1 methods against those baselines.

No remaining actionable defect was found in this final frozen prerequisite. It is coherent for
an **IMPLEMENTED_UNVALIDATED source commit**, with root owning selection. The previously stated
native V2 capability limit, pending membership work, held history deltas and incomplete shared
session/date/bundle callers remain explicit. No test, compiler, performance, device or full-chain
claim is closed by this source review.
