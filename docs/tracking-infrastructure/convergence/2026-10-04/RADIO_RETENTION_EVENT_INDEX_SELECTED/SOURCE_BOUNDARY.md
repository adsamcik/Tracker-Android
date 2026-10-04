# Captured radio event secondary-index companion

Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

Root authorized this necessary execution of existing multi-group callback behavior.
A captured radio callback can have multiple independently qualified original logical
facts; the existing fact primary key already distinguishes them. Only its secondary
(source_kind, source_event_id) index loses uniqueness. No table, identity, payload,
format, permission or new same-version migration mechanism is added.

This five-path packet changes additive current DDL, converts the known 28 event index
in the existing 28-to-29 migration, and versions that one preflight requirement. Supported
old 28 still requires its original UNIQUE index before migration. Final 29 requires the
nonunique index. An incompatible earlier unshipped 29 remains preserved and blocked
with the existing INCOMPLETE_FINAL_V29_SCHEMA result, without rewriting its bytes.

Existing migration/marker lookup additions are preserved in BASE and not owned by
this delta. The packet must follow the ownership selection and its minimal fixture
companion. Runtime owns the corresponding Room entity nonunique declaration, exact
receipt-owner DAO/store selection and consumers; this index packet alone is not a
coherent multi-group source slice. Root must compose its exact runtime dependency.

Two actual fresh-file Room/preflight cases are authored, unexecuted: final 29 nonunique
index admits current schema; earlier 29 UNIQUE index is blocked without changing its
file. The existing actual migration case now proves old uniqueness changes to nonunique,
its original row survives, and a second exact logical fact shares the same callback.
Existing additive-schema TableInfo coverage includes the affected full table/index.

No compilation, test, schema generation/drift, gate, device/provider execution or Git
mutation occurred. This does not establish multi-group runtime correctness or assembly
readiness; the root-owned runtime slice and independent review remain required.
