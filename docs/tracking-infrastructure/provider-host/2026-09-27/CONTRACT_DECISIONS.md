# Remaining provider/host contracts

Status: PROPOSED_NOT_IMPLEMENTED. Implementation-only: no validation, activation, push or integration.

The approved plan specifies optional radio hosting and a nonpersisting shared feature Location lease. It leaves the final feature-owner API as a pre-edit subdesign. These are the concrete implementation choices proposed for confirmation under AGENTS.md.

## Optional radio host

Extend the existing acknowledged BackgroundLocationHost into the existing service host for separately keyed Location/Wi-Fi/Cell ambient leases. Add one saved default-off notification-host choice per radio source to the authoritative preference/policy snapshot. No new service or background scan scheduler. Radio leases require their own exact ambient identity, policy revision, consent, lifecycle authority and lawful foreground promotion; Location consent is never borrowed. Service types equal accepted capture plus acknowledged hosted sources. Ending capture releases only its join. Disabling one hosted source retires only its lease; the last hosted source releases the service. A failed promotion leaves independently consented passive radio collection opportunistic and reports the hosting limit. No active attempts are granted by hosting.

Trade-off: two explicit choices and additive persisted policy fields, rather than a hidden global always-on radio host. Existing users default to current opportunistic behavior.

## Feature Location

Keep core/common UiLocationProvider as the dependency-safe feature interface; bind an engine adapter whose requests acquire a typed nonpersisting lease on the same physical Location owner. Carry unique consumer/lifecycle identity, UI or legal game-service host, source request, observed monotonic time/boot and nullable real wall time. Map keeps its 2s request and at most 10s observed age; games keep their current requested intervals and accuracy limits with maximum age min(2*interval,5s). No invented timestamp or capture/ambient authorization. Cancellation/host loss releases only that feature lease; failed retirement prevents a replacement provider. A game can retain its own legal host through screen-off without granting tracker background intent. A feature-only point never creates WAL/history.

Sunrise/sunset currently starts an unreleased passive provider during initialization. Proposed removal of that separate registration: retain its last known coordinates and existing fallback calculations; refresh its cache only from lawful already-running shared Location observations. It creates no Location demand and no continuous hidden feature lease. This can leave its location stale until another allowed consumer collects a fix.

Trade-off: slightly larger shared-owner adapter versus duplicate providers; the approved one-owner and nonpersisting requirements rule out keeping duplicate acquisition or treating feature points as ambient history. The sunrise cache behavior is newly specified here and needs confirmation.

## Already approved work that continues

Exact claimant matching and rollback, bounded actor settlement with retained retirement evidence, strict acknowledged predecessor retirement, live-capture versus host predicates, and capture/host foreground-mask authentication use existing approved seams. No pending shared-radio WAL marker, aggregate Steps identity or power-recovery contract is inferred approved by this proposal.
