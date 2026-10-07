# Independent legacy Activity-domain review

Reviewer: /root/automatic_stop_fence_review_1007. Original task messages, approved
released-main preservation/session-label behavior, exact V2 source/diff and
committed dependencies supplied. Exact bounded V2 selection statically accepted.

Production source SHA-256:
a6baad56bf752924374ac38648cc064aa9ce464c40df686a554f3640d370201e.
Test source SHA-256:
128d28b0c145b2c6aa1580cefeebda2dd0f5f5292ce846af5b2e9d76aa81877d.
FILES SHA-256:
dd31bd8a5e8c1aef8edaa002a66713182f05a7e0c6dbd57a058bef6fb54333e3.
Patch SHA-256:
621d8fa9a47d348e4cb0aa379fdb807c80b3e053cde48ba1c2c45e96ab22e223.
All 41 dependency pins match selection HEAD 7da374b5f4. Author V1 retained; both
new runBlocking JUnit4 methods explicitly return Unit in final V2.

Released main SessionActivity IDs are user label references, including arbitrary
auto IDs and negative built-in references. Migration 12-to-13 deliberately stores
detected primary_activity as NULL and preserves the label separately; 20-to-21
keeps that distinction. Only the importer fallback crossed those domains.
Removing that fallback copies canonical detected Activity, preserves NULL with
bindNull and retains a genuine canonical 7 even with a different chosen label.
Nullable TripDao and DefaultTripRepository mapping were statically traced.

Two actual Room/import/TripDao cases and original-vault assertions authored;
one existing NULL expectation corrected, twelve other existing methods preserved.
No schema/API/annotation/capture/epoch behavior introduced. The tests do not
execute DefaultTripRepository/UI or production SQLiteX startup. Old session-label
organization relationship recovery and other active-data omissions remain open
in the separate proposed audit. Full T18 remains open. IMPLEMENTATION_ONLY /
IMPLEMENTED_UNVALIDATED; NOT_RUN. No execution gates or provider activation.
