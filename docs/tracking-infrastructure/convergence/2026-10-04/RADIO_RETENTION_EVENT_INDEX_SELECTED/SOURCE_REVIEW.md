# Independent static review — radio retention event index

Date: 2026-10-04. Reviewer: `/root/fresh_radio_schema_review_1004`.
Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

Reviewed the original user requests and approvals inherited from the coordinator,
AGENTS.md, the five exact selected snapshots and patch, the declared ownership
dependencies, current runtime entity/DAO/store and callers, released `main` schema
12 source, the legacy normalization/import path, and authored migration/preflight
fixtures. No source edits, Git mutations, compilation, schema generation/drift,
tests, providers, device or other gates were performed. This review is not a gate
pass or assembly acceptance.

## Result

**Hold this initial selection for S1.** The final 29 index/preflight change itself
is narrow and preserves rows and the existing fact primary key. The runtime
dependency is not frozen yet and has not been accepted by this review.

**S1 — the historical 27-to-28 migration now creates the wrong version-28 index.**
Selected `AdditionalTrackingSchema.kt:82` changes the helper to NONUNIQUE, but
`AppDatabaseMigrations.kt:1394` calls that same helper from `MIGRATION_27_28`.
Selected `DevelopmentV28DatabaseContainment.kt:150–155,197,213` deliberately still
requires the original UNIQUE index for a version-28 database. The checked-in
version-28 schema also declares it UNIQUE (`28.json:10061–10068`), and actual
existing migration fixtures stop at 28, for example
`AppDatabaseMigration27To28Test.kt:93`. Consequently the direct 27-to-28 result
no longer matches its version contract. A normal uninterrupted 27-to-29 Room
chain ends with the correct index; that does not repair the intermediate contract.
Keep historical version-28 creation UNIQUE, then convert it only inside the
existing 28-to-29 migration. Current fresh Room 29 uses the runtime entity's
NONUNIQUE declaration. No new migration framework or same-version rewrite is
needed.

**S2 — one claimed fixture coverage statement is inaccurate.** The boundary says
the existing additive-schema TableInfo coverage includes the affected full
table/index. `ImportedSourceSchemaAssemblyTest.kt:77–138` does not include
`captured_radio_fact_retention_proof`. The new actual migration fixture proves
the index conversion on a minimal five-column table; the new fresh Room case
checks the real index. Either describe that exact coverage honestly or author
the intended full-table parity companion. No test result is asserted here.

## Checked source boundaries

- Known existing 28 preflight requires its original UNIQUE index; the selected
  28-to-29 migration drops and recreates only that named secondary index. Its
  authored fixture retains the original row and admits another logical fact
  for the same callback.
- Final 29 preflight removes only that index from the old-version sentinel
  list and checks a separate NONUNIQUE sentinel. Earlier incompatible unshipped
  29 stays blocked as `INCOMPLETE_FINAL_V29_SCHEMA`; the read-only preflight
  does not rewrite its database. Fresh/blocked cases are authored, unexecuted.
- Released `main` uses schema 12 and `main_database`. The current production
  path creates a fresh final active database and imports a disposable copy
  normalized through 12-to-26, preserving the original legacy file. It does
  not run released 12 through the active 27-to-29 migration chain. The existing
  populated released-12 fixture uses the committed released schema and checks
  imported radio details and original version/rows. Its source remains
  unexecuted, so released migration correctness still awaits convergence.
- Runtime receipt selection must be reviewed against frozen exact bytes before
  composition. The mutable DAO currently has bounded two-candidate lookup and
  exact fact-key lookup, but this is not acceptance of an unfrozen dependency.

## Exact initial pins

- Manifest: `4c6af5a9b886e1c4563f4d462613a836544c6d7161ddd179577a34a7a701cb02`.
- Patch: `c46e441fb6926542b06a73189d2d4e8144e784c80a7d77035729b24395014f46`.
- Boundary: `bdeeac65d1f181a9f4f41b72cfe7edc7e0d5a2ba51c757b5ea2058d0efe7bfc3`.
- Selected AdditionalTrackingSchema: `1e149f1da67f7940eb597982a806217f80d0f07caa55a2fa4e358fb0e651d50b`.
- Selected RadioObservationChildMigration: `4edc1d756190d8430681467189015d6a8bb0af91b713e266f5a0b06ebaaf51f9`.
- Selected containment: `be0be61f760e3b8bbf1017b5810fa3616218770b85d09ee2ee2f4e4e8cfa1590`.
- Selected migration fixture: `ed3fb5dedc47e1e6e4f69f430efa016da9dcda29947c44b2d82cea6f929bff74`.
- Selected preflight fixture: `82e178aa8bff786385853249ebecbe02a2a4ac73852fb5f04ece0ceed5b8b1f3`.

The original rejected packet and this receipt should remain intact when a repaired
selection is composed. All 25 assembly chains and all deferred execution gates
remain open.
