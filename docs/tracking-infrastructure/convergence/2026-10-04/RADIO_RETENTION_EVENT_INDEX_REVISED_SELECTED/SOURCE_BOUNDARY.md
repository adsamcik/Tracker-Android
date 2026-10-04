# Captured radio secondary event index — S1/S2 correction

Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

The initial rejected packet and SOURCE_REVIEW.md remain immutable. The approved
multiple-group callback behavior keeps the original fact primary key and changes
only its secondary event index for final29. No table, identity, format, permission,
new schema version or same-version rewrite is added.

S1 restores the historical AdditionalTrackingSchema helper to UNIQUE. Its only
production caller is MIGRATION_27_28; a direct27→28 result must retain its version28
contract and sentinel. The helper's two test callers construct/repeat historical
DDL. This restored helper has no delta relative to the original selected BASE;
it is preserved as a six-path selection dependency. Only the existing28→29
migration drops/recreates that index as nonunique. Fresh Room29 uses the runtime
entity's nonunique declaration. Earlier incompatible unshipped29 stays preserved
and blocked by the existing final29 read-only preflight.

S2 adds one dedicated full affected-table TableInfo parity case in the existing
ImportedSourceSchemaAssemblyTest. It checks historical-helper uniqueness, performs
the expected29 index conversion in that isolated DDL fixture, compares ALL columns,
foreign keys and named indexes of captured_radio_fact_retention_proof against
actual fresh Room29, then proves repeated historical helper calls do not rewrite
an existing final29 index. This fixture does not execute the full28→29 migration;
RadioObservationChildMigrationTest separately executes that actual migration on
its minimal old table, proving original row retention and two facts per callback.
The two real-file fresh/blocked preflight cases remain. Three new cases and one
extended actual migration scenario are authored, unexecuted. The unrelated general
TABLES parity list is unchanged; previous claims that it included this table were
incorrect and are superseded by this explicit dedicated case.

The cumulative selected patch has five changed paths; restored historical DDL is
a sixth required context path with zero owned delta. Lookup schema/marker additions
are BASE prerequisites, not owned by this slice. Runtime owns the Room entity and
exact fact-aware receipt DAO/store/consumer changes; root must compose and review
that dependency before acceptance. Retained/imported resources and all original
fact rows remain unchanged by the index conversion.

No compiler, tests, lint, schema generation/drift, gate, device/provider execution
or Git mutation occurred. This is source authorship, not migration/runtime proof,
whole-chain completion or integration readiness.
