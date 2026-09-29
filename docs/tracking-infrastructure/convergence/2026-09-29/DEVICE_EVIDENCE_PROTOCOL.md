# Six-source device evidence protocol

**Preparation only — IMPLEMENTATION_ONLY.** This protocol is not an activation
receipt. Do not run it until the final source and test input is frozen, the
compile/Hilt/Room/migration/`ciUnitTest`/`ciCheck` gates pass, and bounded local
provider activation is explicitly authorized. Record the exact commit, build,
device, Android version, permissions, enabled source set, quality level, clock
zone and test interval before each run. Keep provider payloads out of logs and
shared evidence; use opaque IDs, counts, gap reasons and screenshots with
personal data removed.

## Observe one complete chain per source

For each supported source, first enable **only that source**. Record the
provider registration and delivery identity, admitted WAL receipt, retained
source fact, bounded day/range history result, visible Dashboard/Tracker/detail
result, a file in the one selected export format, re-import duplicate handling,
and retirement after Turn off tracking. A session adds a time annotation and a
quality preference without becoming a prerequisite for all-day collection.
Repeat the source-only path with a manual session; for automatic sessions,
record the separate Activity trigger authority and verify that trigger-only
Activity does not appear as recorded Activity. Mark unsupported device/provider
paths **Unavailable** with the actual reason, not as a pass or a measured zero.

| Source | Native evidence and source-specific checks |
| --- | --- |
| Steps | Direct counter boundaries and supported on-device provider windows; zero, positive, reboot/reset and unknown spans; local-calendar daily total without a session; no double count across session/import overlap. |
| Pressure | Sensor event time, covered windows and gaps; rate/FIFO behavior; timestamped readings and truthful bounds. |
| Location | Active and system-rearmable passive delivery, temporary My location/game lease freshness, one physical owner, real accuracy and gaps; exact saved points in selected file. |
| Activity | Recorded changes at observed times, separate control-only transitions, automatic start support/repair, no invented duration. |
| Wi-Fi | Passive callback versus budgeted active request, saved available network children, empty/unchanged delivery, physical age, one Wi-Fi file containing selected session/background observations. |
| Cell | Passive callback versus budgeted active request, saved available tower children, multiple subscriptions where supported, one Cell file; never record subscriber/SIM IDs. |

For Wi-Fi and Cell, repeat with Location independently enabled. A qualified
Location fix may add an **observed device position** in the existing radio map
view, with its uncertainty. Disable Location and verify radio collection and
history continue, map context becomes unavailable, and no radio action starts
Location. Android may still require a location-related permission or Location
Services to expose radio readings; record that separately from whether the
Location tracking source is selected. Deleting either source invalidates only
affected context/results.

## Joined lifecycle and recovery

- Test a representative subset including Steps+Location, Wi-Fi+Cell, and all
  six where supported. Add and remove a source during a session. Check one
  physical registration per source, independent background purpose lifetime,
  and stable facts for the remaining sources.
- Start from app, notification, widget and shortcut. If permission repair is
  needed, grant it and verify only the unchanged, one-use Start continues.
  End the session separately from Turn off tracking on every surface. A late
  callback or permission result must not restart collection after Off.
- Repeat with process loss, service restart/refusal, reboot, provider change,
  Location Services off, permission revoke/regrant, battery saver and Doze.
  Record the exact recovery or gap state and verify that saved earlier history
  remains until retention or explicit erase. Android force-stop is reported as
  a platform limitation, never as a successful background continuation.
- Export, import, selected deletion, retention and full clear must preserve
  independent sibling purposes. A cancelled transfer must leave no app-owned
  incomplete copy; an external unfinished copy must be disclosed for removal.

## Quality and battery

Use the same device, route/activity, enabled sources and observation duration
for each of the five settings. Record requested and applied plan, registration
time, event/batch counts, active radio requests, wakeups, first useful result,
coverage/gaps and Android power/trace measurements. Include idle all-day and
live-session runs. Compare fidelity and cost with the protected legacy path
where it exists. Steps continuity and passive radio callbacks should remain
cheap; a session may favor quality only for participating sources. Do not
publish a battery percentage or claim an improvement from a host test or from
unmatched runs. A rung with the same physical mechanism should be described
honestly until device evidence supports a different claim.

## Exit record

For every run, save the commit/build/device metadata, source and purpose,
observed chain IDs/counts, UI and file observations, lifecycle transitions,
measurement artifact references, failures and limitations. The exit decision
is **pass**, **fail**, or **unsupported/unmeasured** for each chain. A fresh
independent reviewer then receives the original user requests and decisions,
final diff, host-gate results and these device records before local `dev/v10`
integration. Never turn an authored scenario or an emulator simulation into
physical-provider evidence.
