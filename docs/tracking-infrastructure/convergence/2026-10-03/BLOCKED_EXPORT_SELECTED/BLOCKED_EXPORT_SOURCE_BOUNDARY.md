# Database-open export boundary

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** This selects five source paths;
no compilation, tests, device, schema or integration checks were executed.
Exact selection and byte hashes: `BLOCKED_EXPORT_FILES.json` and
`BLOCKED_EXPORT_SELECTION.patch`. Two mixed files use HEAD-based snapshots;
held Location import and radio/settings drafts remain excluded.

## Approved behavior

The user answered “Disable exports until the database opens.” The blocked legacy
recovery screen therefore removes its independent file launcher and Export button;
its fallback message asks the person to retry import. Existing Retry and eligible,
confirmed deletion remain. Preserved legacy/migration data exports in normal
Settings require the existing Ready startup generation, including when recording
is turned off. No blocked-startup bypass or new access rule exists.

Both normal Settings writers now share one private helper and the existing
`withReadyGenerationTransfer` boundary. It owns the blocking output stream on IO,
checks cancellation on writes/close, and waits for physical close and unfinished
destination cleanup before releasing the transfer. Cancellation is rethrown.
The legacy repository itself closes its stream before marking its vault exported;
a cancellation after that successful commit retains the completed external copy.
The wrapper closes its underlying stream at most once after a successful close.

## Authored source scenarios

Eight ViewModel cases cover Blocked, retryable open failure, generation change during
reconciliation, retained export after collection Off, cancellation/draining before
retry, cancellation after a completed legacy copy and cleanup provider failures.
The independent review repair preserves the original cancellation even when cleanup
also throws, and turns ordinary provider runtime failures into `CleanupRequired`.
Two Compose cases cover
the actual blocked screen without Export, fallback copy/Retry and the preserved
eligible-delete confirmation. Existing stream mocks now write the passed wrapper.
These are authored cases, not passing results.

## Remaining work

Persistent later removal notices belong to the separate unfinished-file slice.
This helper currently retains the existing delete-or-truncate cleanup result;
failed external cleanup must subsequently be connected to the approved existing
export storage. Complete data-only backup/restore and the full privacy chain stay
open. Source review cannot establish Hilt/Compose compilation or Android provider
behavior; those gates remain deferred until complete assembly.
