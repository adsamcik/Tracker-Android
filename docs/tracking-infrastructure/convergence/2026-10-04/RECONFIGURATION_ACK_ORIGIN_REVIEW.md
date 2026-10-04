# Independent trace: acknowledgement origin after live reconfiguration

Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

Review-only static trace of the current candidate and frozen Cell source. No source edits, executable gates, provider/device activation, Git mutations or recursive delegation. This diagnosis records existing contracts, not a new architecture. File lines describe the exact hashes below; later authoring may move them.

## Existing authority rule

`AuthoritativeSessionCoordinator.kt:2291-2378` appends a manifest and source actions with `POLICY_RECONCILIATION` while keeping the original service run and original `run.startOrigin`. It updates the run's plan/lease/revision, without rewriting its original start reason.

`SessionManifestIntegrity.kt:71-127` already authenticates the complete run timeline. The prepared manifest must have the original run origin; every later consecutive manifest must have `POLICY_RECONCILIATION`, an appropriate change reason and valid chronology. An exact acknowledged action should match its selected manifest's origin. Matching the original run origin applies only to the prepared manifest.

## Concrete remaining blocker R11

### Steps shared lifecycle authority

`DefaultSourceLifecycleProofAdapter.kt:336-376` sends session Steps recording proof through `historicalSessionAuthority`. That helper authenticates the complete timeline at 2230, selects the exact manifest and bounded source action at 2274-2298, then its predicate at 2374-2375 requires both `action.startOrigin == manifest.startOrigin` and `action.startOrigin == run.startOrigin`.

After a valid manual session changes settings without ending its run, the later manifest and action have POLICY_RECONCILIATION while the original run remains MANUAL. That valid later observation fails the second comparison. The exact same failure applies to an automatic original run. This concrete trace concerns session proof; it does not establish a defect in the separate all-day Steps branch. The helper also serves legacy Cell aggregate-owner qualification; native v6 Cell uses its source qualifier instead.

### Pressure central proof

`PressureFactProofAuthenticator.kt:310` authenticates the complete timeline, and 516-521 selects the exact later-manifest action. Its predicate at 1076-1077 repeats the same two incompatible origin equalities. This rejects a valid later-manifest Pressure observation despite the accepted timeline.

The central proof is used by lifecycle recording/materialization, exact fact lookup, History, stored-lineage retention and selected-session deletion. This review does not infer an untraced writer failure from those reader failures.

### Smallest existing-contract repair

Keep exact action-to-selected-manifest origin equality and every existing scope, policy, consent, registration and acknowledgement-time condition. Restrict the original-run origin comparison to the prepared manifest:

```kotlin
startOrigin == manifest.startOrigin &&
(manifest.manifestRevision != run.preparedManifestRevision || startOrigin == run.startOrigin)
```

The callers already validate the complete timeline, so later manifests remain restricted to legitimate POLICY_RECONCILIATION. No public API, new storage, rewritten run origin or broad provenance relaxation is needed. Author positive same-run settings-change cases and negative mismatched first/later action-origin cases for both existing predicates before freezing a repair.

## Six-source trace boundary

| Source | Traced result for this exact origin defect |
|---|---|
| Location | Existing qualifier authenticates the complete timeline at 458; recording delegates to that qualifier. No analogous unconditional action-to-run origin comparison found. |
| Activity | Native admission validates the complete timeline at 482. Portable accepted-action validation matches the selected manifest's origin, without the erroneous unconditional run comparison; portable timeline validation also remains present. |
| Steps | R11 concrete blocker in the shared session lifecycle helper described above. |
| Pressure | R11 concrete blocker in the shared central proof described above. Separate deletion timeline uses the original origin only for its initial manifest and expects POLICY_RECONCILIATION later. |
| Wi-Fi | Runtime author is repairing analogous comparisons in its qualifier, maintenance, portable and History readers. Working changes are not accepted as a frozen repair by this receipt. |
| Cell | Native v6 qualifier validates the complete timeline at 403 and has no such source-action predicate. The reviewed R8 same-run policy fixture uses first MANUAL and later POLICY_RECONCILIATION without a Cell provenance relaxation. Legacy aggregate-owner use of the shared helper remains affected by R11. |

These are narrow negative/positive static traces of one contract. They do not declare any source's complete chain, device behavior or assembly ready.

## Inspected current-source SHA256 pins

- Coordinator: `6a18a578cbf68cf0a28dbb64bd792d4561f356216e97a0f66cfd3db73ed13357`.
- SessionManifestIntegrity: `b8f904e77abf0906a1679eb9fdc55add67487d0289bfa1f57f97584352f21833`.
- Lifecycle adapter: `55312a35ce57da3c5a58ff87f939b7eac9a37103407a4ebcd1ddf92f71ab6bd9` (includes separately authored working changes; not a selection receipt).
- Pressure proof: `3279f4bf4448da0c34fb45c45547a10fafb33de60c9028df8729eaec4a80fd44`.
- Location qualifier: `4e658cd80de7d3f131d84e404d2fb535567319dbf0ad3cf1dffd4ad4e228f6ca`.
- Activity admission: `cd26ffbb4637181f3a9495e6801db4aa85be58758c1d3ffc5040f39af22f32b9`.
- Activity portable: `786e3ed52c469e993c12bb91ec9b4e21a011d9742595c58cc0e005b91f8aa433` (reviewed N5 selected pin).

R10 empty Cell settlement, R11 repair, Wi-Fi follow-ups, remaining transfers/full chains and final convergence remain open. All 25 assembly prerequisites remain pending. No authored case is claimed passing.
