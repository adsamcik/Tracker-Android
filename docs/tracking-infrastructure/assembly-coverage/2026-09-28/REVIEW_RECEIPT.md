# Independent static review receipt

**IMPLEMENTATION_ONLY.** A review-only subagent inspected the assembly trace, scenario catalog,
scheduled-export path comment and surrounding source. No build, test, schema, emulator or device
validation ran.

The review found three scenario wording errors that were corrected: exhaustive source-mask
readiness must be distinct from representative full tracking flows; migrated radio details must
retain their legacy origin without gaining a native receipt; and disabling one all-day source
must be distinct from the global Turn off tracking command. It also asked for explicit automatic
capture, cross-day radio export, map uncertainty/gaps and stale permission-return cases. Those
cases are now in `SCENARIOS.md`.

The reviewer traced a real implementation gap in both Dashboard and Tracker: permission and
Location Services callbacks can retry Start after a later End, Turn off or settings change.
Widget/shortcut Dashboard navigation currently carries only an unqualified Boolean. The user
approved one-use continuation of the original Start across screens. The low-level durable
generation compare-and-reserve seam and focused test source are authored but unvalidated;
the ticket, UI wiring and full lifecycle tests are still pending.

The reviewer also traced two Steps switches in the unfinished candidate and the startup policy
seam. The user corrected the release baseline: the released `main` branch had one Steps switch
and AppDatabase schema 12; the split candidate and v27/v28 schemas were never released. The
approved new choice records Steps throughout the day and carries the released Steps selection
forward. A final schema check corrected S23 so the released schema-12 fixture contains only
its actual tables; Pressure, corrections and export ledgers belong to separate unfinished
candidate fixtures. No migration or UI change is claimed complete here.

All twenty-five assembly prerequisites remain pending until their final production/test chains
and deferred convergence and device gates are completed.
