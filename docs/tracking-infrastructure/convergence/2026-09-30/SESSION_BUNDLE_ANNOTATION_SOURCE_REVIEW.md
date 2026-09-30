# Session bundle annotation storage — source review input

**IMPLEMENTED_UNVALIDATED**, 2026-09-30. Production implementation, focused test
sources and static review only. No compilation, Gradle, tests, schema generation
or drift, lint, device, CI, activation, integration or Git operations by this agent.

## Approved storage

The existing local annotation receives two nullable TEXT columns without defaults:

```sql
ALTER TABLE organization_annotation ADD COLUMN imported_source_selection TEXT;
ALTER TABLE organization_annotation ADD COLUMN imported_activity_label TEXT;
```

`NULL` preserves ordinary local interval grouping. An explicit empty JSON array is
an imported metadata-only session. Other arrays name exact source entries, days
or source-file archives, with no timestamp fallback or provider authority.
Descriptive imported activity text is not a foreign local Activity ID and does not
create a global activity label.

## Source path

- Common `OrganizedSession` appends its nullable typed membership. Four source-owned
  identity shapes use the existing `HistorySource`: Location file archive, captured
  entry, background Steps day, background Wi-Fi/Cell archive.
- Every identity uses the existing lowercase 64-character source ID. Selection is
  bounded to 4,096 unique references and at most 1 MiB of local JSON. The codec
  rejects unsupported kinds, extra fields, invalid source/kind combinations,
  malformed IDs and duplicate references.
- The Room entity appends both fields with null defaults, preserving positional
  callers. Every branch of the composed DAO UNION returns the two corresponding
  fields. Native and legacy virtual sessions return null.
- The existing Room organization repository decodes membership within its bounded,
  epoch/retention-protected snapshot. Invalid stored metadata reports Unavailable;
  it never becomes interval grouping. Imported descriptive label text is used only
  when there is no local activity label.
- Name, dates and zone edits preserve exact membership. Choosing a local activity
  replaces imported descriptive text. Existing remove and retention operations
  preserve source observations and remove only organization metadata.

## Focused authored scenarios

- All source identity shapes round trip; explicit empty and null remain distinct.
- Count, byte, identity, source/kind, uniqueness and extra-field limits reject.
- Real Room annotation reads and edits preserve exact references despite unrelated
  source data at the same time.
- Descriptive imported labels do not create global Activity rows.
- Unsupported stored membership reports Unavailable.
- Selecting a local activity replaces the descriptive import label, and removing
  the annotation leaves source data intact.
- Existing organization paging, exact editor, epoch/revision, live recording,
  uncertainty and validation scenarios remain authored in the same test source.

## Independently inspected coordinator schema companion

`RadioObservationChildMigration.kt` adds the exact two ALTERs above.
`DevelopmentV28DatabaseContainment.kt` requires both nullable TEXT columns without
defaults only in the v29 preflight. The migration fixture preserves the old row's
name and verifies null values and pragma shape; the containment fixture rejects a
missing selection column. Historical v28 fixtures remain unchanged. This static
inspection is not evidence of a passing Room migration or generated schema gate.

## Shared files and remaining assembly

The organization DAO also contains the product agent's separately authored bounded
`byAdapters`/`byIds` reads. Those are preserved; its history reader consumes exact
typed membership. Bundle worker/runner/source-receipt mapping is a separate slice
and is excluded from this annotation manifest. Export UI placement/source chooser
is still pending the user's answer. Final schema generation, Hilt/compile/Room and
convergence/device evidence remain deferred.
