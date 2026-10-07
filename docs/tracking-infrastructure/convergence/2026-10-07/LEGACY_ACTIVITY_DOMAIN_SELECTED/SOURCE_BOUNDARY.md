# Preserve detected Activity semantics during legacy import

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. All test source is NOT_RUN. No gates,
compilation/tests/schema/CI/device/provider work, Git mutation or recursive delegation occurred.

Routine correctness repair authorized by root from the original approved data-preservation rule:
main's session_activity_id is an arbitrary chosen label, not Android detected Activity.type.
MIGRATION_12_13 explicitly writes primary_activity=NULL and preserves the chosen label separately;
MIGRATION_20_21 preserves legacy_activity_id. The importer alone incorrectly substitutes that ID
through COALESCE. The selected production change removes only this source-expression override.
The existing canonical nullable primary_activity is copied exactly; genuine detected values survive.
Location/Cell/Wi-Fi canonical fallbacks remain unchanged.

Actual Trip and SessionSegment fields are nullable. TripDao aliases the value unchanged. The model
mapper preserves it; DefaultTripRepository resolves NULL+LEGACY_MIGRATION to UNKNOWN. No reader
API/behavior changes are needed. This fixes false Walking/Cycling/etc from label ID collisions.
It does not yet attach the original label to new session organization; that storage proposal remains
unapproved. It creates no native owner, consent, manifest, receipt or source membership.

Two new methods extend the existing populated Room import fixture: a real schema12 database with
custom label ID7/name My cycling label must keep unknown detected Activity; compatible normalized
v26 with explicit detected7 and distinct label123 must retain detected7/confidence. Both inspect
the actual TripDao result and original vault. One existing expectation is corrected to NULL for its
explicit NULL detected field plus unrelated label7; the fixture itself is unchanged.

The original main12 JSON fixture is byte-identical to the current committed copy and already used
by this test. Other existing test methods and all fixture/setup/import cleanup are preserved.
Framework SQLite/Room and Robolectric fixture sources are used; execution, physical providers,
complete platform SQLiteX containment and final convergence remain unproven/NOT_RUN.

Only two clean committed paths are selected privately. BASE and WORKING_BEFORE preserve raw
bytes; SELECTED uses UTF-8 LF. Shared importer/test files and frozen unrelated drafts are untouched.
No new fields, schema, annotation backfill, UX, dependency or architecture is implemented.
Independent review is pending; root owns staging and commits.
