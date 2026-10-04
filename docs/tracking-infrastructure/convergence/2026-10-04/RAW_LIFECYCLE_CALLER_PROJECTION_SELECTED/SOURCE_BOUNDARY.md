# Existing raw lifecycle caller authority projection

Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

This explicit two-path companion selects the preserved raw lifecycle projection repair
and its actual Room regression. `RawSessionLifecycleIntentVersion` already expects 21
storage classes including nullable `source_caller_authority_reference`; the old 20-field
projection rejected valid rows or lost the caller reference required by checksum callers.
The query now projects that existing field only when its real SQLite storage class is
text/null. Malformed BLOB content remains rejected by the original strict validator.
No field, schema, permission, identity domain or new qualification contract is added.

The original ownership lookup selection accidentally includes this test while omitting
its preserved production projection. That rejected freeze stays intact for evidence.
The revised lookup selection must omit this unrelated testcase and select it only here.
BASE/SELECTED snapshots exclude lookup hooks, runtime candidates and unrelated R11 drafts.

One actual DAO/Room case is authored: an existing caller reference round-trips exactly,
then replacing its storage value with BLOB makes validation fail. It was not executed.
No compilation, tests, schema/check gates, provider/device execution or Git mutation occurred.
