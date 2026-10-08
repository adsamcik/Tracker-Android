# October 8 continuation decisions

Explicit human answers supplement the original implementation plan. They do not
close T25 or authorize provider activation or execution gates. Implementation-only
work and source-test authorship continue; authored tests are NOT_RUN.

## Approved behavior

- Keep the calculated Tracker average from the same saved Location points as
  distance, excluding recording gaps and stationary intervals. A measured stationary
  route may show zero; incomplete data stays Partial.
- Present one measured History reading with both overlapping session names in its
  expanded details. Keep the list compact. Names follow actual organization intervals
  and imported membership; session labels do not create or duplicate observations.
- Calendar marks all days with saved tracking data using one simple dot. This replaces
  Location-distance shading and its legend; the selected day's shared list remains.
- Live Location preserves valid coordinates and original measurement time when
  optional accuracy, altitude, speed or bearing are missing or invalid. Store/display
  those details as unknown. My location preserves its freshness requirement. Games
  with explicit accuracy requirements and optional radio joins keep those requirements.
- Use the shared chosen-source setup step for normal startup and Start. Manual and
  automatic sessions use the same recording pipeline. Preserve older recordings,
  prepare again after the actual completed Clear all, and continue only the still-valid
  original manual Start. Source failure remains local; healthy sources remain usable.
  Location keeps the current writer/storage. Automatic setup preserves the original
  live callback, existing trigger lifetime and Android enqueue deadline; later setup
  cannot grant fresh authority to an expired action.

The exact refined setup proposal is
`AUTOMATIC_SHARED_SETUP_REFINED_PROPOSAL/PROPOSAL.md`, SHA-256
`22bf2815010b63ec8b42b96b05867ee06e5b293b06b866d2806ae2454c93bcd2`.
Its independent source acceptance and lifetime conditions are in
`AUTOMATIC_SETUP_SIMPLICITY_REVIEW/REFINED/`. This records architecture approval,
not completed implementation or execution evidence.

## Launcher widget removal supersedes the pending refresh proposal

The user asked whether the discussed widget was for Android launchers. After that
was confirmed, the user instructed: **"Let's remove it"**.

The discussed feature is the existing Active Session launcher widget and receiver.
Remove its registration, implementation, provider XMLs, preview and exclusive
resources/helpers/test sources. Remove its scheduler call and now-unused widget
History entry-point dependency. No thirty-second refresh observer is needed.
Other existing launcher widgets are separate features and are outside this removal.

The earlier unanswered widget-refresh question is superseded. Do not interpret
the clarification questions as approval of refresh behavior.

## Boundaries

The portable native saved-authority and durable-WAL materialization repairs implement
the previously approved original-recording rule. A later command generation, quality
change, End or Off must not hide valid original readings. Actual callback admission,
current writer ownership, exact Stop boundaries, deletion, retention and epoch checks
remain distinct. Unknown optional measurements do not warrant losing coordinates.

All 25 full-chain prerequisites and T25 remain open. No Gradle/build/test/lint/schema,
provider/device/battery/CI, integration or push has been performed for these decisions.
