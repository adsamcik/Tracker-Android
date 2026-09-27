# Independent schema/wiring review

2026-09-27. **STATIC_REVIEW_ONLY; IMPLEMENTED_UNVALIDATED.**

Review-only `/root/schema_wiring_review` inherited the original user messages, follow-ups,
approved decisions and supplied AGENTS.md. It inspected current candidate base a0b39fd96c,
production assembly/call sites, exact scoped diff and authored test sources. No edits, recursive
delegation, compilation, schema generation or validation was authorized.

Initial review identified the Pressure sentinel type mismatch, missing Ambient count-domain and
captured-radio retained-proof dependencies, and index admission that ignored partial/uniqueness
semantics. These are corrected with existing typed sentinels and dependency lists. Static review
accepted the eight code/test paths with no blocking finding. It confirmed default BINARY DDL,
actual imported receipt damage failing graph authentication, and actual Cell proof-only state change.

Reviewer flagged virtual test timeouts around real Room callbacks as a reliability consideration.
New observer waits now use Dispatchers.Default timeouts, keeping a bounded real-time wait while
allowing the test coroutine to collect. Affected static review accepted those revised sources and the additional generic retained-proof
recording observer with no blocking finding. It requested one documentation correction: exact Steps
SQL/FK/trigger-body authentication belongs to the Room create/reopen callback, while preflight
checks selected sentinels and marker. The trace now separates those stages. The AppDatabase builder
KDoc also records the actual production/test factory boundary. All authored tests remain uncompiled
and unrun.

Static trace found no extra missing entity/DAO/DI binding in the selected families. This does not
establish generated Room parity, compiled Hilt correctness or full pending contract completion.
Configurable fileBuilder is test-used and not production-contained; Pressure/owner trigger bodies
are not exhaustively authenticated by preflight. Those limitations remain explicit. Whole schema
convergence and assembly gate stay open; no main integration, provider activation or push.
