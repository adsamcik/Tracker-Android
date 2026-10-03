# Committed exporter reflection source selection

**IMPLEMENTED_UNVALIDATED.** Base: `3291c4b15df99194daee7c4135b4f223e1a36a93`.
No compilation, tests, shrinking, quality gates, device work or Git mutations ran.

`EXPORTER_REFLECTION_FILES.json` lists the exact nine owned paths and source hashes.
`EXPORTER_REFLECTION_SELECTION.patch` selects only eight `Keep` imports/class
annotations and one new reconstruction test. Its SHA-256 is
`36579f7d29f8efdb722153279dc4271357e5d6d4e3b1f264481ae045dd9a382e`.
Only mixed `DatabaseExporter.kt` has selected before/after snapshots; its backup
scope, cancellation and checkpoint draft changes are excluded.

## Static production trace

The committed registry supplies GPX, KML, JSON, Database, Steps, Background Steps,
Activity, Pressure and Location exporters. The Export screen receives their
class through its Intent and uses `getConstructor().newInstance()`. The eight
previously unprotected classes now use the existing AndroidX `Keep` mechanism;
Location already uses it. Existing implicit or all-default public constructors
are preserved. No factory, registry, dependency or consumer-rule change is needed.
The library already declares its consumer rules and the app enables release
shrinking with Android's default optimized rules; minified execution is deferred.

## Authored scenarios and exclusions

`RegisteredExporterReconstructionTest.kt` contains one parameterized test over
the nine committed IDs. Each obtains the actual registered class, reconstructs
its public no-argument instance and compares type, MIME/extension, date selection,
Location requirements and sensitivity metadata. It does not inspect annotation
strings or claim R8 execution. No database or provider is opened by this test.

Held radio exporter classes/registry changes, mixed `FormatRegistryTest`, backup
behavior, transfer/scope drafts and dirty radio consumer rules remain excluded.
This slice fixes preservation of the existing reflection path; it does not close
source transfer, session scope, full backup or Location Replace implementation.
