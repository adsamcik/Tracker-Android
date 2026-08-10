# Phase 6 location device matrix

This matrix is the release gate for event-owned location acquisition. The pure JVM matrix is
implemented by `LocationPrerequisiteEvaluator`; rows requiring Android provider behavior remain
instrumented/device-lab gates and must not be inferred from unit tests.

| Dimension | Required cases | Expected application |
| --- | --- | --- |
| API level | 26, 28, 29–30, 31–32, 33, 34, 35, 36, target 37 | No API-only registration crash; typed blocked/degraded result |
| Backend | FUSED / FRAMEWORK | Exactly one selected backend; framework remains available without GMS |
| GMS | present-current / absent / outdated | Only FUSED is blocked when unavailable |
| Location permission | none / coarse / fine | none blocked; coarse degrades precision and high-accuracy request; fine applies requested precision |
| Background location | granted / denied on API 29+ | automatic background location is blocked when the permission is required and absent |
| Start context | already-foreground session / manual foreground / automatic background | background FGS illegality is typed as `BACKGROUND_START_ILLEGAL` |
| Location Services | enabled / disabled | active modes blocked when disabled; framework passive observation remains a best-effort request |
| FGS capability | location type present / missing | missing capability blocks before provider registration |
| Plan mode | disabled / passive / low power / balanced / high accuracy / probe | applicable plan and precision degradation are explicit |
| Power state | normal / screen-off / Doze / power saver / low battery / thermal | provider delivery variance measured; requested interval is never treated as a guarantee |
| Lifecycle | start / reconfigure / quiesce / process restart / full deletion | one registration generation; old callbacks fenced; provider completeness remains unobservable |

Automated Phase 6 coverage:

- exact provider-observation deduplication after event-time sorting;
- coarse/fine, GMS-free framework, GMS-blocked fused, Location Services, and automatic-start legality;
- all legacy-owner/GPS-tier/location-enabled flag combinations;
- reordered route replay, distance parity, teleport rejection, and discontinuous reacquisition.

Physical verification still required before event-owned location is enabled by default:

- Fused `flushLocations` and removal on current and outdated Play Services;
- framework provider fallback on GMS-free API 26/28/34+ devices;
- Android 14–16 automatic background-start exemptions and while-in-use permission behavior;
- OEM background restriction, force-stop/package-stopped, notification-channel blocked, and
  permission revocation during registration;
- route/distance golden fixtures recorded on representative devices.

