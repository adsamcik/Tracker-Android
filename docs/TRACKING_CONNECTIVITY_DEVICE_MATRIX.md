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
  attribution. Linked results retain the applicable plan revision.
- Active Wi-Fi attempts are not scheduled in Doze. Broadcast observation remains registered;
  attempts and operational outcomes are telemetry, not durable source observations.
- The containment budget permits paid work only for direct session capture: at most one Wi-Fi
  scan request or one Cell refresh group per physical registration, closed early by qualified
  durable evidence. Control/ambient/enrichment receive no paid attempts. Any repeated-attempt mode
  requires TI-212 device calibration before it can become user-visible policy.
- Wi-Fi admission retains each qualified `ScanResult.timestamp`. Startup cache reads, failed scan
  updates, stale/unknown children, and synthetic freshness heartbeats are not persisted. A
  provider-confirmed fresh empty result is retained as identifier-free zero coverage.
- Cell callbacks are pinned to subscription-scoped `TelephonyManager` instances. API 31+ uses
  `TelephonyCallback.CellInfoListener`; API 26-30 uses `PhoneStateListener` plus cache/refresh
  fallback. Provider timestamps are retained per cell observation.
- Provider and subscription identifiers are withheld from new radio source events until a
  per-install, consent-epoch-scoped HMAC and rotation path is implemented. Aggregate counts,
  bands, radio types, quality, coverage, and per-item provider time remain available.
- `wifi-interpolation` continues through the Phase 4 bracketed-location join; `cell-presence`
  continues through the bounded prior-location join. Both remain `CanonicalWriter.LEGACY` until
  their Phase 7 domain-owner gates are accepted.

## Automated matrix

| Area | Cases | Expected result |
|---|---|---|
| Wi-Fi permission | API 26+ precise location for `startScan()`/`getScanResults()` | Missing required grant is `BLOCKED/PERMISSION_MISSING`; `NEARBY_WIFI_DEVICES` is not requested for this scan-only path |
| Wi-Fi provider | Wi-Fi feature absent; Location Services off | Explicit hardware/provider block |
| Wi-Fi idle | active attempts in and out of Doze | Doze is `DEGRADED/DOZE`; no attempt deadline is submitted |
| Wi-Fi delivery | fresh, mixed-age, failed, empty, and replayed callbacks | Fresh children retained once in effect; stale/unknown omitted; failed cache read omitted; confirmed empty is zero coverage |
| Wi-Fi attempt | accepted, false/throttled, permission exception, provider exception | Telemetry and bounded backoff only; no attempt/outcome row in the observation WAL |
| Cell permission | radio feature, fine location, phone state | Missing prerequisite is explicit and blocks registration |
| Cell API | API 26-30 listener; API 31+ callback; API 29+ optional refresh | Callback evidence is qualified; unsupported refresh does not create a polling loop |
| Cell subscription | requested SIM set, active SIM fallback, no active SIM | Requested IDs win; otherwise active IDs; finally unscoped manager |
| Cell freshness | unchanged set/same timestamp; unchanged set/new timestamp | Exact duplicate suppressed within the live runtime; new modem timestamp retained; restart-stable replay remains blocked |
| Scheduling | overlapping Wi-Fi and cell windows | One coalesced service wakeup |
| Budget | direct capture, ambient/control, first qualified evidence, exhaustion | Direct capture cannot exceed its finite registration budget; ambient/control schedule no paid request; qualified evidence closes the budget while passive callbacks remain |
| Ownership | legacy/event owner combinations for Wi-Fi and cell | Event-owned source never constructs its legacy producer |

## Physical-device release matrix

Run each applicable row with source-event ownership enabled and confirm there is exactly one
framework registration per enabled source.

| Device condition | Wi-Fi checks | Cell checks |
|---|---|---|
| API 26, 28, 29, 30 | broadcast age, failed-update/cache rejection, scan throttle | legacy listener and API 29+ optional refresh callback |
| Android 12 / API 31 | scan broadcast and screen-off delivery | `TelephonyCallback`, single and dual SIM |
| Android 13-16 / target 37 | precise-location grant/denial and Location Services state | permission revocation during callback registration |
| Location Services disabled | blocked status; no provider call | blocked when required location access is unavailable |
| Screen off / power saver | attempt count does not exceed the finite direct budget | callbacks continue; refresh count does not exceed the finite direct budget |
| Doze | zero app-initiated scans; one deferred status per idle interval | no timer storm; callback/cache behavior recorded honestly |
| Wi-Fi scan throttled | `THROTTLED`, increasing backoff, cached result age visible | N/A |
| Single SIM / eSIM / dual SIM | N/A | callbacks reconcile without persisting raw subscription ID; identity-free tier reports partial completion truthfully; later product identity requires a non-identifying registration-local slot contract |
| Airplane mode / radio absent | N/A | radio-unavailable/provider state; no stale presence claim |
| Permission revoked while active | callback is fenced; next application is blocked | listeners removed or failure classified; no post-cutoff admission |
| Force-stop / package stopped | no implied restart or scan delivery | no implied restart or callback delivery |
| OEM background restriction | observed execution delay and attempt count recorded | observed callback loss/cache age recorded |

For each run capture requested plan, applied status, attempt count/hour, provider calls/hour,
coalesced wakeups/hour, accepted snapshot count, oldest accepted age, throttle/timeout count, and
registration count. Phase 7 passes only if provider calls never exceed the selected finite
source policy budget, useful evidence meets the approved quality floor, and event ownership
produces no second legacy registration.

Android references:

- <https://developer.android.com/develop/connectivity/wifi/wifi-scan>
- <https://developer.android.com/reference/android/telephony/TelephonyManager#requestCellInfoUpdate(java.util.concurrent.Executor,%20android.telephony.TelephonyManager.CellInfoCallback)>
- <https://developer.android.com/reference/android/telephony/TelephonyCallback.CellInfoListener>
- <https://developer.android.com/reference/android/telephony/PhoneStateListener>
