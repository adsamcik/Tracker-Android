# Last-session selection guard

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** This is a two-path source slice,
not scheduled export completion, assembly freeze or validation evidence.

The user requires LastSession and incremental exports to preserve their selected
scope. The existing worker previously filtered out completed sessions with
uncertain or missing bounds while selecting the latest session, which could
substitute an older session in the same page or a later page.

The worker now selects the first completed, inactive session before validating
its dates. An uncertain, missing, negative or reversed interval returns the
internal Unavailable outcome. The existing worker failure/log path handles that
outcome before resolving a destination, writing data or advancing a watermark.
No completed session in a page still permits normal page traversal; active and
failed acquisitions do not become completed selections. Valid original intervals
keep their existing behavior, including source-only and retrospective sessions.

Four new focused selection cases and one corrected existing case are authored,
unexecuted. They cover an unavailable latest session with a valid older session,
missing dates before further page traversal, invalid dates and active/failed rows.
They are helper-level cases, not executed worker/Room/serializer evidence. Complete
selected-identity payload propagation, epoch/revision/final handoff fencing and
scheduled file round trips remain required work. No source-wide revision,
provider demand, new database field, format, dependency or public UI is added.

The manifest pins a HEAD-based narrow worker snapshot and its patch, excluding
held unfinished-file cleanup, backup/restore, format and radio drafts. Preserve
those drafts in the working tree. Only the task-related test delta is selected.

No Gradle, compilation, test, lint, schema/diff check, device/emulator, UI,
battery, CI, provider activation, integration or push was performed.
