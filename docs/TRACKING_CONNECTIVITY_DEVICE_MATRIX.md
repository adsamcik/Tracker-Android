# Wi-Fi and cell source-runtime matrix

This document is the Phase 7 acceptance matrix for the source-event tracking rework. Automated
rows are executable contract coverage. Physical rows remain release gates because Android scan
throttling, modem callbacks, OEM idle policies, and multi-SIM behavior cannot be proven by JVM or
Robolectric tests.

## Runtime contract

- Wi-Fi and cell share one app-scoped `CoalescingSourceWakeupScheduler`; there is no timer per
  source and no alarm-based cadence engine.
- A configured interval is a **minimum interval between attempts**, never a delivery promise.
- Wi-Fi provider broadcasts without a causally linked app attempt use `RECEIVE_TIME_ONLY` plan
  attribution. Linked results retain the attempt ID and revision.
- Active Wi-Fi attempts are not scheduled in Doze. Broadcast and cached-result observation remain
  registered and a durable `DEFERRED_IDLE` status is emitted.
- Wi-Fi snapshots retain the newest `ScanResult.timestamp` monotonic time and explicit age.
  Unchanged fingerprints are suppressed only for the configured window, after which a freshness
  heartbeat may be recorded.
- Cell callbacks are pinned to subscription-scoped `TelephonyManager` instances. API 31+ uses
  `TelephonyCallback.CellInfoListener`; API 26-30 uses `PhoneStateListener` plus cache/refresh
  fallback. Provider timestamps are retained per cell observation.
- Provider identifiers are represented in source events by stable SHA-256-derived tokens. The
  existing canonical tables remain legacy-owned during shadow parity.
- `wifi-interpolation` continues through the Phase 4 bracketed-location join; `cell-presence`
  continues through the bounded prior-location join. Both remain `CanonicalWriter.LEGACY` until
  their Phase 7 domain-owner gates are accepted.

## Automated matrix

| Area | Cases | Expected result |
|---|---|---|
| Wi-Fi permission | API 26-32 fine location; API 33-37 fine + nearby Wi-Fi | Missing required grant is `BLOCKED/PERMISSION_MISSING` |
| Wi-Fi provider | Wi-Fi feature absent; Location Services off | Explicit hardware/provider block |
| Wi-Fi idle | active attempts in and out of Doze | Doze is `DEGRADED/DOZE`; no attempt deadline is submitted |
| Wi-Fi cache | fresh, stale, unchanged within/outside dedupe window | Stale rejected; unchanged suppressed; freshness heartbeat retained |
| Wi-Fi attempt | accepted, false/throttled, permission exception, provider exception | Separate requested/outcome evidence and bounded exponential backoff |
| Cell permission | radio feature, fine location, phone state | Missing prerequisite is explicit and blocks registration |
| Cell API | API 26-30 listener/cache fallback; API 31+ callback | Equivalent callback/cache evidence; old refresh API is visibly degraded |
| Cell subscription | requested SIM set, active SIM fallback, no active SIM | Requested IDs win; otherwise active IDs; finally unscoped manager |
| Cell freshness | unchanged set/same timestamp; unchanged set/new timestamp | Exact duplicate suppressed; new modem timestamp retained |
| Scheduling | overlapping Wi-Fi and cell windows | One coalesced service wakeup |
| Budget | minimum interval plus increasing backoff | Deadline never precedes either constraint |
| Ownership | legacy/event owner combinations for Wi-Fi and cell | Event-owned source never constructs its legacy producer |

## Physical-device release matrix

Run each applicable row with source-event ownership enabled and confirm there is exactly one
framework registration per enabled source.

| Device condition | Wi-Fi checks | Cell checks |
|---|---|---|
| API 26, 28, 29, 30 | broadcast/cache age, scan false/throttle behavior | legacy listener, cached `allCellInfo`, API 29+ refresh callback |
| Android 12 / API 31 | scan broadcast and screen-off delivery | `TelephonyCallback`, single and dual SIM |
| Android 13-16 / target 37 | fine + nearby permission permutations | permission revocation during callback registration |
| Location Services disabled | blocked status; no provider call | blocked when required location access is unavailable |
| Screen off / power saver | attempt count does not exceed plan budget | callbacks continue; sparse refresh respects minimum interval |
| Doze | zero app-initiated scans; one deferred status per idle interval | no timer storm; callback/cache behavior recorded honestly |
| Wi-Fi scan throttled | `THROTTLED`, increasing backoff, cached result age visible | N/A |
| Single SIM / eSIM / dual SIM | N/A | subscription identity preserved and callbacks reconciled |
| Airplane mode / radio absent | N/A | radio-unavailable/provider state; no stale presence claim |
| Permission revoked while active | callback is fenced; next application is blocked | listeners removed or failure classified; no post-cutoff admission |
| Force-stop / package stopped | no implied restart or scan delivery | no implied restart or callback delivery |
| OEM background restriction | observed execution delay and attempt count recorded | observed callback loss/cache age recorded |

For each run capture requested plan, applied status, attempt count/hour, provider calls/hour,
coalesced wakeups/hour, accepted snapshot count, oldest accepted age, throttle/timeout count, and
registration count. Phase 7 passes only if provider calls never exceed the selected minimum
interval budget and event ownership produces no second legacy registration.

Android references:

- <https://developer.android.com/develop/connectivity/wifi/wifi-scan>
- <https://developer.android.com/reference/android/telephony/TelephonyManager#requestCellInfoUpdate(java.util.concurrent.Executor,%20android.telephony.TelephonyManager.CellInfoCallback)>
- <https://developer.android.com/reference/android/telephony/TelephonyCallback.CellInfoListener>
- <https://developer.android.com/reference/android/telephony/PhoneStateListener>
