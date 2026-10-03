# Independent imported Wi-Fi export cursor review — 2026-10-03

Reviewer: `/root/fresh_bundle_context_review_0930`. **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED**. Review only; no production edits, Git, executed tests or other convergence/device/provider gates.

## Narrow finding and source closure

**No concrete defect found in the selected continuation fix or new fixture by static inspection.** The held exporter's two cursor assignments now use the source reader's actual newest-member pair. This fixes a concrete omission when a logical entry began earlier but has a later physical run; using its envelope start/identity can skip the following page.

- `ImportedWifiDao.kt:470–499` selects the newest physical member's start and same-member identity, sorts both descending and applies an exclusive continuation predicate to those two values.
- `RoomImportedWifiProductEvaluator.kt:124–148` passes the existing request cursor to that bounded DAO, reads at most `limit+1`, evaluates the first page and reports lookahead. Its existing candidate-page predicate (`715–741`) verifies strictly descending member pairs including the supplied cursor; `918–933` preserves/authenticates that pair against the saved source value.
- `PortableWifiSourceExporter.kt:279–284` takes the last **source candidate**, even when export selection skipped that candidate, and continues with `newestMemberStartTimeMs` and `newestMemberIdentity`. It preserves bounded 100-entry requests, cancellation checks, source qualification, per-entry selection and existing error behavior. It does not infer a cursor from emitted records or change any date/membership/identity rules.

This is routine reuse of the existing source paging contract, with no new schema, public API, format, abstraction or unapproved product choice. Complete held exporter publication and its other scope/identity decisions remain separate work.

## Authored regression fixture

`ImportedWifiExportPagingRoomTest.kt:53–93` exercises the actual exporter, Room transaction, private spool and V2 codec, with source reader pages explicitly doubled. The first page contains 100 entries whose envelope starts are `1..100`, but newest runs descend from `1099` to `1000`. A following entry has newest run `999`. The double returns that second page only for the exact source pair. The test asserts both the decoded 101 original entries and the next request's start `1000` plus the first page's final run identity. The previous envelope cursor would silently produce only the first 100 entries and fails these assertions.

The fixture contains valid portable runs/entries with explicit unresolved coverage, no observation children and no native source facts (`95–123`). This avoids inventing Wi-Fi scans. It supplies source qualification as a double and does not execute the real source evaluator or import receipt path. Actual Room/codec assembly is covered in test source, not proven to pass. **One authored method, unexecuted.**

## Exact pins and selection boundary

| Input | SHA-256 |
| --- | --- |
| Whole new `ImportedWifiExportPagingRoomTest.kt` | `7bafc5b9ab971bfcd897e20d2e73f550c0fdf6a42830767b868ec2eec1c8b26a` |
| Current held Wi-Fi exporter whole snapshot | `2b0d9588e15782ecbd434272cd38b958c4608c73efc021d5d3464fba696a238d` |
| Exact `appendImportedSessions` UTF-8 excerpt, preserving line endings, through the next `exportBackground` marker exclusive | `2621c7a848793856c619471b2aeb89495904ed6a877f0cc242bfcdcc57cb8331` |

Only the two cursor assignments and their explanatory comment are reviewed for source selection; the function hash anchors their surrounding preserved dependency. The rest of the exporter remains held under its other review packets. This receipt is not passing execution, full export completion, assembly freeze or `dev/v10` readiness.
