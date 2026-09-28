# Plain-language product decisions

Date: 2026-09-28. These four tool questions replace the earlier three technical questions;
the earlier questions can be ignored. The user approved all four recommended choices.
The approvals authorize the specified behavior, not a claim that its implementation is complete.

## 1. Source setup and settings

Current: first-run setup offers Location, Activity, Steps, Wi-Fi and Cell, omits Pressure. Denied
permission turns off a chosen source; denied motion permission turns off Activity and Steps.
Recommendation: show all six sources; Pressure starts off until chosen. Keep chosen sources
selected but show how to repair missing permission/hardware. Use one source list in settings
with session/all-day participation and quality controls, preserving legacy/custom preferences
and independently consented sessionless all-day tracking. Unsupported modes remain explicit.

Tool question: May I make these changes? Choices: Yes, use one six-source list and keep choices
selected (Recommended); Keep the current setup choices and separate controls. State: approved,
2026-09-28.

## 2. Setup and notification settings recovery

Current: setup lacks visible save error; retention failure can leave setup marked complete without
opening the app. Notification settings can spin forever after load failure and quick saves race.
Recommendation: error plus Retry, preserve edits, serialize saves, and finish setup only after
settings and retention preparation succeed.

Tool question: May I use this recovery behavior? Choices: Yes, show errors and finish only after
successful saves (Recommended); Keep current completion order; add error messages only.
State: approved, 2026-09-28.

## 3. What live surfaces display

Current: Tracker, notifications and widgets still show some older location-based values; a session
without Location may misleadingly show zero distance. Recommendation: show only chosen sources;
show values only from trustworthy saved source data; use short Waiting, Partial or Unavailable
states otherwise; preserve notification customization.

Tool question: May I use this display rule? Choices: Yes, show only trustworthy chosen-source data
(Recommended); Keep the current layout; add status messages. State: approved, 2026-09-28. Approval does
not establish currently unfinished data-reader/retained-authority correctness.

## 4. Accessible notification order

Current: notification items can only be reordered by dragging. Recommendation: keep dragging and
add Move up/Move down actions that change the same saved order for accessibility.

Tool question: May I add those actions? Choices: Yes, add accessible Move actions (Recommended);
Keep drag reordering only. State: approved, 2026-09-28.

Existing source freedom, consented sessionless ambient, five balance levels, 48-hour CONTROL
retention and selected-format-only export remain settled. Earlier retained Location visibility/proof
storage, exact ownership indexing, unsupported metric periods/open intervals, runtime/host/automatic
and restore decisions remain separate. Elapsed time is not consent; continue independent work.
