# Approved shared saved moving average

IMPLEMENTED_UNVALIDATED, held for independent source review; seven new methods NOT_RUN.
The human approved retaining the calculated Avg from shared saved points, excluding stationary
pairs and gaps. Extend the existing constant-memory distance accumulator and its existing
temporary snapshot state with moving distance/duration; both finite and live saved readers use
the same result. No new observer, data store, provider request or growing-session rescan.
Use original same-clock elapsed deltas for qualified native pairs; legacy pairs retain their
existing valid wall-time continuity. Measured stationary pairs report0; no measured pair stays
null. Known gaps keep available measured sections Partial. Sensor instantaneous speed is not
the route average. Source qualification/continuity, epoch, deletion and rebuild remain shared.

Tracker Avg uses existing localized speed formatting and existing Partial/Waiting/Unavailable
presentation. Remove the superseded feature-local preview-path calculator/test, whose only
remaining references were its own class and test. No other metrics or map joins are widened.
Seven methods cover actual accumulator semantics/snapshot resume, real qualified Room finite
and live reads, presentation and Compose display. Provider/grant setup is the existing explicitly
seeded fixture; no physical producer/service/Android lifecycle proof is claimed. All are NOT_RUN.
Source-local clock/window ordering, effective corrections and broader numerical consumers remain
open; this does not close all25/T25 or any full-chain prerequisite. Mixed drafts preserved.
No compile/test/lint/schema/device/provider/CI/integration/push performed.
