# All-day Location resource use

Date: 2026-10-22. Delivery is the enclosing local `dev/v10` commit; no push.
Full host verification passes: 16,022 cases, zero failures/errors and seven existing
skips. All nine new Location regressions pass within the 4,508-case engine suite.
Final independent source and evidence reviews pass with no remaining blocking findings.
Independent source review of the combined changes has no blocking findings.

## Changes

The existing planner and physical Location owner remain shared by all-day
collection, sessions and temporary foreground requests. No new sensor, timer,
settings mode, dependency or storage is added.

For adaptive all-day Location at Balanced with precise permission available:

| Evidence | Request |
| --- | --- |
| Motion unknown | High accuracy, 30-second target, 15-second minimum, 60-second batch allowance |
| Fresh movement | Original chosen target: high accuracy every 10 seconds at Balanced |
| Confirmed stationary | Balanced power accuracy, 30-second target, up to 60-second batch allowance |
| Battery Saver or moderate battery pressure | Balanced power accuracy, 30-second target |
| Critical battery pressure | Existing low-power target, never source Off |

Confirmed stationary targets across the five quality levels are 120, 90, 30,
30 and 30 seconds. Existing demand floors cap delivery delay at 120 seconds;
the lower levels therefore cannot always batch. These are provider requests,
not delivery guarantees. Fresh accepted movement restores the selected cadence.
Unknown motion retains active acquisition without requiring Activity or Steps.
Valid delayed readings still use the existing storage checks and cannot renew
current motion merely on receipt.

Balanced retains the existing 30-second stationary cadence so Wi-Fi can still
find its two Location endpoints within the approved 30-second matching window.
Review rejected a 60-second target because it would match almost no intervening
Wi-Fi readings. No join rule or source dependency is changed; lower power modes
still deliberately trade context density for battery use.

Live sessions retain their existing quality preference. My location and games
still raise the same physical request independently of background battery
preferences. Legacy/custom plans, disabled sources and consumer floors retain
their existing behavior.

Passive-only GMS requests now use their declared target as the minimum callback
interval, matching the existing framework path. Another app's high-rate fixes
can no longer cause unbounded Tracker callbacks. This limits app wakeup and
processing cost without requesting additional Location acquisition.

Every policy refresh still checks current permission, demand membership,
retention, data epoch and physical registration. After those checks, an unchanged
plan does not rewrite its registration pointer or immutable plan record, and
identical callback contexts are retained. A changed revision, authorization,
sink, claim or freshness budget keeps the existing handoff. Queued callbacks
continue to wait for that handoff and retain their measurement identity.

## Basis and checks

Android recommends reducing unnecessary accuracy/frequency, allowing batching,
and bounding opportunistic callback rates. Actual savings depend on hardware
and system/provider behavior. [Android battery guidance](https://developer.android.com/develop/sensors-and-location/location/battery/optimize),
[GMS request semantics](https://developers.google.com/android/reference/com/google/android/gms/location/LocationRequest.Builder).

Nine new focused regressions cover unknown/moving and stationary restoration,
battery/live/custom/floor behavior, actual owner wiring, delayed motion evidence,
unchanged registration pointers, changed membership/epoch and changed callback
freshness. Existing queued-callback and changed-authorization tests are strengthened.
The final host result is recorded with the [reliability coverage](TRACKING_RELIABILITY.md).
No device battery, real provider delivery or on-device latency claim is made.
