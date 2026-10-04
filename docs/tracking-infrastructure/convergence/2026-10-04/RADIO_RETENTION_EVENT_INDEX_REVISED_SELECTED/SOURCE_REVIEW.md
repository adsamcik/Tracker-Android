# Independent source review — revised radio event index

Status: narrow static acceptance, IMPLEMENTED_UNVALIDATED. No compilation, tests, Room generation/drift, other gates, Git mutation, provider or device work was executed. All 25 full-chain assembly prerequisites remain open.

## Exact selection

- FILES.json: `75b6bdff34ad51aebdf02c820ec58c1a4b32af3a0af8797a20d47e11a07f4056`.
- SELECTION.patch: `ec58d5a3e426236e0e27d1489ffc3b2f8db52a80ee5384aa03fca863371d1b46`.
- SOURCE_BOUNDARY.md: `1157a270ca370c5e8bc539632fd8d4210137801f0596fd2d265916c9e71cb2bb`.

All six selected snapshot hashes match the manifest. Reviewed the approved multiple-fact receipt dependency in the frozen Cell packet (PACKET.json `91d3a5723e6809c61abd27269eb2a8804e1d0ff369304154bc0f23b18ebc8607`); its Room entity keeps the existing four-part fact primary key and declares only the event secondary index nonunique. Original IDs and rows are preserved.

## Rejected findings resolved

**S1 resolved.** AdditionalTrackingSchema.kt:82 again uses the original UNIQUE index. Its sole production caller remains MIGRATION_27_28 at AppDatabaseMigrations.kt:1394. Thus the historical direct 27-to-28 result and version28 preflight sentinel remain consistent. The selected historical helper is byte-identical to its original BASE. MIGRATION_28_29 in RadioObservationChildMigration.kt:37-39 drops/recreates only the named secondary index as nonunique; fresh Room29 is governed by the selected runtime entity. Final29 preflight separately requires nonuniqueness, while version28 still requires uniqueness. An incompatible earlier unshipped29 remains preserved and blocked; there is no same-version rewrite.

**S2 resolved.** ImportedSourceSchemaAssemblyTest.kt:65 now explicitly compares full TableInfo for captured_radio_fact_retention_proof against fresh Room after the expected index conversion, checks original helper uniqueness, and confirms repeated historical helper calls do not rewrite a converted index. This is an isolated DDL fixture. The separate RadioObservationChildMigrationTest calls the actual migration, retains its original row and permits a second fact for the same callback. The two file-based fresh/unsupported29 preflight fixtures remain. Three new cases and one extended migration scenario are authored and unexecuted. The revised boundary correctly distinguishes these fixture scopes.

## Released and unknown database boundary

The earlier source review traced main's released version12 data through the existing preserved legacy import path: normalization occurs on a disposable copy, current storage is freshly created at29, and the original legacy database remains intact. This index slice does not change that path, the unknown-database preservation policy or any schema version. Existing populated released12 import coverage remains authored, not proven by execution. It is incorrect to describe this as an in-place released12-to29 upgrade.

No remaining blocker was found in this narrow schema/migration correction. Full runtime acceptance still depends on closing the separately reported Cell child authentication gap and completing the unchanged convergence gates; this receipt does not declare the feature, schema assembly or integration ready. The first rejected packet and its review remain preserved.
