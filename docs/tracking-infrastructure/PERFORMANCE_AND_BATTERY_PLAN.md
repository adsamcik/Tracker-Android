# Tracking performance and battery plan

## Outcome

Make End responsive, keep automatic sessions useful in Android Battery Saver, and reduce the cost of screen-off and all-day tracking. Preserve route points through the original End cutoff, the session’s independent all-day sources, and existing permission, consent, retention, and replay guarantees.

Compare the product with `main` at `59fda665c` and isolate the performance changes against `dev/v10` at `128d0d2ce`. The October 29 API 37 emulator run recorded 42.919 seconds from End to presentation. Its last Location generation retired at 22.463 seconds and saved Location settlement continued to 38.299 seconds. Use those numbers to choose where to instrument. Compare new timings with fresh runs on the same input and device. [Core-flow evidence](convergence/2026-10-27/CORE_FLOW_COMPLETION.md#current-status--october-29)

## Work in order

1. **End settlement and automatic Battery Saver.** Commit `20b34fbac` combines the two Location-prefix checks and applies a balanced-power Location cap to automatic sessions during Saver, including prepared and recovered sessions. Astra 6.2 reviewed the patch. Compile and run the regression tests, then exercise a recorded route with all-day Location enabled. Record the time from End to acknowledged cutoff, last provider retirement, saved-route settlement, and final UI. Compare with the baseline. If a phase still takes tens of seconds, profile that phase before changing it. Verify the exact pre-End route, retry without duplicate points, post-End points outside the closed route, and continued all-day collection. Toggle Saver during an automatic session and confirm the same logical session survives, the applied plan drops and restores, and manual-session quality keeps its existing policy.
2. **Adaptive all-day Location in poor coverage.** The candidate uses precise Fused Location for up to three minutes of unknown motion at Balanced. It then alternates a four-minute balanced-power window with a 90-second high-accuracy retry driven by the existing service tick. The 30-second requested cadence remains during unknown motion. Credible movement triggers a refresh that restores the configured cadence. The timing describes requests on a running host and requires on-device validation. Keep silence distinct from confirmed stillness. Check the lowest preset for request churn around the 120-second evidence expiry. Compare indoor idle, indoor-to-outdoor movement, and the first part of a walk or drive. Retain the stationary cadence that Wi-Fi needs for its existing Location matching window unless paired coverage measurements support a change.
3. **Passive Location delivery.** Measure the five-second receiver budget, writer-lock wait, and per-fix authority cost with large and contended deliveries. Reuse checks only across fixes with the same authenticated authority and observation-time boundary. If that falls short, evaluate a bounded durable delivery handoff with idempotent replay. Exercise cancellation, duplicate delivery, permission revocation, deletion, and an End cutoff inside a delivery. Compare receiver lifetime and review saved points and exact purpose membership.
4. **Steady-state reconciliation.** Count database checks, platform requests, plan revisions, and provider replacements during a quiet hour. Coalesce unchanged processing through existing permission, settings, motion, and power signals. Keep a watchdog for missed changes and finite retries. Check that revocation, Saver, thermal pressure, expired motion evidence, foreground changes, and unavailable providers still reconcile promptly.
5. **Hardware-specific behavior.** Measure Pressure at Balanced and live quality where the sensor FIFO is unavailable. Compare callback rate, CPU time, and useful pressure or altitude resolution before reducing cadence. Measure the framework Location backend independently, including the network-provider-unavailable path. Evaluate framework batching and flush where supported. Verify final-batch delivery and route coverage before changing the fallback.

## Acceptance and delivery

- Use the repository’s focused tests and required quality gate for each integrated change. Review consequential patches independently with Astra 6.2. Treat source review, host execution, Android execution, and physical energy measurements as distinct evidence.
- Use only an existing configured emulator or device at the highest stable Android API available in this test environment. The October walkthrough used API 37. A matching ready target is required for new Android execution. Record each tested build, device, settings, route input, known limits, and evidence.
- Use repeated same-phone comparisons for screen-off idle with all-day Location off, an equivalent recorded route, and an automatic session entering Saver. Measure opt-in all-day Location and Pressure separately. Compare energy, GNSS active time, app CPU and wake-lock time, provider deliveries, writer transactions, and database growth. Pair them with time to first useful fix, route gaps, distance, provider retirement, and End latency. See the [device evidence protocol](convergence/2026-09-29/DEVICE_EVIDENCE_PROTOCOL.md#quality-and-battery).
- Keep measurement data local and use aggregate counters for app diagnostics. Integrate reviewed, verified changes into local `dev/v10` and record any blocked test or device case with its specific cause. Claim latency or battery improvement only for workloads that were measured with equivalent tracking results.

## Status

| Item | State |
| --- | --- |
| End and automatic Battery Saver | Implemented in `20b34fbac`, statically reviewed. Focused tests and Android verification are pending. |
| All-day Location | Candidate `703c00984` covers timed accuracy reduction, an unassisted retry, Fused fallback, and movement recovery in test source. Coverage around the lowest preset’s evidence expiry and real power use remain unmeasured. |
| Passive Location | Fresh Location-only permission checks replace full device snapshots. Added test source for real grant mapping and revocation during authority lookup. Writer wait, deadline behavior, and authority batching remain open. |
| Steady-state reconciliation | Removed a duplicate settings read on all-day Location refresh. A quiet-hour trace is needed to choose the next reconciliation change and confirm that required recoveries still run. |
| Pressure and framework Location | Physical-device measurements pending. |
| Local integration | Automatic approval review blocked Gradle under the implementation-only clause in `AGENTS.md`. The permitted API 37 test target has yet to be identified in this checkout’s environment. |

The two source commits are retained on `codex/tracking-end-and-saver` for the next verification run.
