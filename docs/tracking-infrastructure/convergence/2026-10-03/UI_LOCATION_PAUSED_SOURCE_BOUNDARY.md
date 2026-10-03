# Explicit Location request over a paused background plan

**IMPLEMENTED_UNVALIDATED / IMPLEMENTATION_ONLY.** Base: coordinator-confirmed clean paths at
`16458b8a59`. Sources are frozen for independent static review.

## Exact scope

`UI_LOCATION_PAUSED_FILES.json` pins exactly two owned source deltas; `UI_LOCATION_PAUSED_SELECTION.patch`
is generated from the preserved `ui-location-paused-selection/{before,after}` bytes without Git.
No other runtime, UI, settings, dependency, manifest, storage, or API source is selected.

The approved explicit My location/game request must override normal tracking quality limits.
Previously `SharedLocationSourceController.reconcileAfterUiLeaseChange` treated a known paused
background demand as an unknown cold owner and rejected the temporary request.

The predicate now exempts only an already reconciled **disabled** `ambient:location` baseline with
the same current policy revision and no session identity. Unknown/cold demands, newer revisions,
foreign ambient consumers, and unjoined sessions remain fenced. The existing physical runtime still
checks Android permission/availability and surviving registrations, and a replacement after rejection
still requires acknowledged `closeShared` retirement. Existing exact UI leases, requested rates,
freshness filters, and cancellation cleanup are reused. No durable recording demand is created or
resumed, and UI-only callbacks never enter the WAL.

## Authored source scenarios

Six new, **unexecuted** controller cases cover:

1. Paused baseline → My location → release, preserving the background demand without durable refresh.
2. Map/game leases across background pause and restoration, including independent and repeated release.
3. Failed physical retirement fences replacement; the failed lease is removed and a later request retries.
4. A newer unreconciled background revision remains protected.
5. An unjoined session demand remains protected.
6. A foreign ambient consumer remains protected.

Existing cold-surviving-request, broker-read failure, UI freshness, shared-owner, and retirement cases
remain unchanged. These are controlled physical-runtime fixtures, not proof of native providers,
device timing, battery, Hilt, Room, or rendering. No tests, compilation, Gradle, lint, schema/diff checks,
devices, provider activation, CI, integration, or Git operations were performed by this slice.

## Review exclusion

Review only the two manifest paths and their exact selected delta. Earlier Steps Statistics, transfer,
radio/import/export/Location Replace drafts, and all pending design-dependent work are excluded.
The physical runtime, source broker, real map/game callers, build files and manifests were read as
dependencies and are not selected or changed. This slice does not close the full-chain ownership
prerequisite, declare assembly complete, or authorize integration into `dev/v10`.
