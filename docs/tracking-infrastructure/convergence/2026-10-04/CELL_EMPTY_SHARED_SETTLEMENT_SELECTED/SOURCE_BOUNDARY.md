# Cell empty shared settlement — source boundary

Phase: IMPLEMENTATION_ONLY. Status: IMPLEMENTED_UNVALIDATED. No gates, compiler, tests, devices, providers, activation, Git mutations or recursive delegation ran.

## Scope

The source-owned Cell portable reader now recognizes a COMPLETE drained session prefix while its accepted physical registration remains ACTIVE for another purpose. Only that path uses the existing exact validated ACKNOWLEDGED run-retirement row, matching completeness, retired capture demand and an authenticated sibling-only closing authorization at the exact cutoff. Source/run/instance/generation/boot/epoch, physical fingerprint and registration chain checks remain. Later sibling-only authorization revisions need not end the shared provider. The retired-provider path remains unchanged in meaning; genuine provider removal is still required there. No new schema, API, store, format or global barrier is added. Empty acquisition still reports NO_RETAINED_OBSERVATION and PARTIAL, never proof of no physical readings.

## Static production trace

SourceBroker.retireSessionDemandsInTransaction writes retirement boundaries and rotates authorization at the cutoff. SharedCellSourceController.settleSessionCutoffLocked refreshes the remaining purpose then awaits CellSourceRuntime.sharedSessionCutoff. That runtime drains the callback prefix and reports NOT_REGISTERED/NOT_SUPPORTED/COMPLETE while keeping the shared join. AuthoritativeSessionCoordinator persists the exact ACKNOWLEDGED SourceRunRetirementEntity and matching SourceSessionCompletenessEntity. The reader consumes those existing rows in its bounded Room snapshot. Cell's existing SessionManifestIntegrity timeline already accepts later POLICY_RECONCILIATION manifests under an original MANUAL run; no Cell origin relaxation was authored.

## Authored, unexecuted actual Room scenarios

1. Empty completed replacement exports while background remains ACTIVE; empty/PARTIAL output and no global callback barrier.
2. Historical capture remains exportable after later capture consent revocation.
3. Later valid independent background revision preserves the earlier completion.
4. Missing exact retirement receipt rejects without sink output.
5. Mismatched app-drain receipt rejects without output.
6. Missing sibling-only closing authorization cannot be borrowed from another purpose.
7. Closing revision that still includes the ended capture rejects.
8. Later sibling-only revision cannot substitute for the original cutoff.
9. Demand retired before the acknowledged cutoff rejects.
10. Stale physical data epoch rejects despite valid completion records.

The fixture uses existing real Room entities/DAOs and the production source-owned exporter with an actual sink. It does not execute a native provider or prove runtime/device behavior. Existing retired zero-callback cases remain authored unchanged.

## Exact selection / dependencies

Select only SELECTION.patch against the pinned BASE text. BEFORE preserves actual original bytes. The existing historical Cell source/fixture are prerequisites; these snapshots do not independently replace the full assembly. Preserve Cell R7/R8 frozen artifacts and root's unrelated adapter test additions. Broader same-run acknowledgment parity for Steps/Pressure, ordinary/export Pressure identity checks, radio representation/import membership and full History remain separate open work. All full convergence and device evidence remain deferred.
