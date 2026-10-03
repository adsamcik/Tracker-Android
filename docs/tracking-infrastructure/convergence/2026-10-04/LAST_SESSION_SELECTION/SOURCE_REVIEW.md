# Last Session guard — independent source review, 2026-10-04

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Review by
`/root/fresh_bundle_context_review_0930`, using original user requirements,
approved scope rules, `AGENTS.md`, the exact HEAD-based selection and actual
caller/DAO/test source. No Git, builds, tests, gates, schema checks, providers or
device operations were performed. Parent supplied base
`2d01ed0ee411bf2bc48d4c45fe070de6b945f102`.

## Result

No actionable defect found in the two-path selected guard. Coherent for an
unvalidated local prerequisite commit; scheduled exact session export remains
unfinished.

## Trace and scope

- Selected `ExportPlanWorker.kt.after:390–401` chooses the first completed,
  inactive row in the existing organization ordering **before** checking dates.
  Uncertain, missing, negative or reversed bounds return `Unavailable`; an older
  row cannot replace it. Active and failed rows retain their existing exclusion.
  Valid source-only and retrospective rows retain their exact range behavior.
- The existing `OrganizationAnnotationDao.visible` composition/order provides
  the same organization list used by this worker. Selected worker lines 372–388
  retain 64-row page traversal and the existing readiness check. A non-null
  `Unavailable` result returns immediately; another page is read only when the
  current page contains no completed inactive row. A short exhausted page still
  yields `NoSession`. The obsolete helper `lastSessionScope` is not a production
  call path and retains its existing test-only behavior.
- Selected worker lines 152–155 exhaustively handle all three scope results.
  `Unavailable` maps to the existing `PlanExportResult.Failed` before incremental
  range calculation, destination reservation or writing. The existing admitted
  worker logs that failure and does not update the watermark. `NoSession` remains
  the existing no-op outcome; a valid selection does not acquire a moving point
  watermark merely because Last Session was chosen.
- The patch changes only this guard and four focused helper test methods, plus
  one existing expectation that previously skipped an uncertain completed row.
  Read the fixtures and constructor seam. The missing-date case exercises the
  non-null result on which the actual loop returns; it is not a worker/Room paging
  test. All five changed methods are **unexecuted**.

The mixed actual worker includes held plan-load/unfinished-file cleanup changes;
those were not accepted by this selection. The test snapshot equals its actual
source after newline normalization. No format, public UI, schema, dependency or
new admission was selected. Full identity/membership propagation, source-specific
payload selection, epoch/organization/final-handoff fences and scheduled round
trips remain required. This receipt closes no full assembly chain.

## Exact pins

| Input | SHA-256 |
| --- | --- |
| Selected worker before | `ae2cac3523c93d9899e355b8131ec26f20f38e63f084fa5384236f07f2548abb` |
| Selected worker after | `2e81d35cd26bf212af649827d26e8ac88f86659ca6dc71eeff89158acccab326` |
| Actual mixed worker | `dba9522c656aaa5ebcba0e998bdc779d9d7741905bd8d9672d00b1f9e010d737` |
| Selected test before | `b8297655eea89688d5d075b581a511ace78b7e94229c46482e36db959902e36c` |
| Selected test after | `a93de8da8f974aad702181d9187626ba57876eae04400589427d0d9c9132961f` |
| Actual test | `875c4fa5099c38f1e372a1db3082fb0f82d5c7f7586b7682354a86eaaa27e2ff` |
| Manifest | `a4224ecd8e8c565e44e5851ab0cbac1d0214a186c8a6c4d1dfd7c081168679dc` |
| Patch | `779fe263f25f712f8d037692fa983ac92731ff0a00161c8279b17bf7b0db65ba` |
| Boundary | `d52322c784321e2734b90c71245e3b76755fdc605d747c0f26b04c0b7487a93f` |
