# T03 selected foundation

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Tests NOT_RUN. Source inspection only.
Base: 78eb255e5958b3490c44fa648b5bb6a67b2af7fe. Exact selected paths/bytes in FILES.json.
No live source, index, commit, build, test, schema generation, provider activation or integration was changed.

## Implemented in this isolated source selection

- One nullable Steps recording-origin membership field, validation and exact raw DAO projections.
- Existing null rows use exactly the old manifest-v2 checksum bytes. Marked rows use an origin-covered
  v3 checksum including nullable values for every membership. This is local manifest integrity, not
  a new counter payload/fact/file wire contract.
- Existing unshipped 28-to-29 migration adds the nullable field; no old recording is inferred.
  Same-version final-v29 containment requires the exact nullable INTEGER DEFAULT NULL column.
  Incomplete development29 files remain preserved and blocked. Main schema12 is the only released
  baseline. The explored v30 alternative is preserved under PRESERVED_V30_ALTERNATIVE and excluded.
- One source-local lifetime predicate validates contiguous marked membership, same source/consent/
  writer/run/boot/zone/rollout and next actual recording boundary. Legacy null retains next-global-
  manifest limits. It is ready for the coordinated retained reader reuse, not yet wired there.
- Adaptive direct Steps requests fixed5-second batching at all five levels. Legacy/custom frequency
  targets stay unchanged. Android actual batching/power has not been measured.

## Authored coverage (not executed)

Actual 28-to-29 populated migration keeps old membership null and the new column exact;
actual Room raw readers preserve legacy-null/origin/continuation and reject wrong SQLite type;
checksum legacy fixture and origin mutation; marked interval across two quality revisions; real
boundary, legacy strict cutoff, missing middle membership/manifest, permission/writer/run/boot/zone
changes; incomplete development29 preservation; actual adaptive factory to resolver to physical
fingerprint across five levels/live sets and permission/legacy/off cases. Existing raw and five-level
fixtures are updated for the approved contract.

## Remaining, not represented as completion

Fresh generic caller/source-set/replay rules remain unchanged. No demand or receipt retention,
operation-generation exception, provider/runtime preflight, registration attribution, admission,
retained reader/maintenance/portable projection or composed1000-to1009 proof is authored here. Root is
reviewing the operation-lease realization discovered in current code; no undisclosed stale-authority
rule has been implemented. The full T03 and all25 chains remain pending.

Mixed working drafts are not overwritten. Root must select exact source bytes after independent
review and compose them with other candidate slices. The final convergence gates remain deferred.
