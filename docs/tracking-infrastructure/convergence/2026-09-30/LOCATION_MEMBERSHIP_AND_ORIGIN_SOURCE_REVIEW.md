# Location file membership and retained origin

**IMPLEMENTED_UNVALIDATED**, 2026-09-30. Production and test source only.
This receipt does not close Location replacement, session bundles or map wiring.

## Exact selection

The membership paths and authored scenarios are listed in
`LOCATION_ARCHIVE_MEMBERSHIP_FILES.json`. Required preserved preview and stream
companions selected with them are:

- `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/file/LocationFileImportPreview.kt`
- `feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/file/LocationFileImportPreviewTest.kt`
- `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/FileImportStream.kt`
- `feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/FileImportStreamTest.kt`

The approved small future-point origin record selects these additional paths:

- `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/LocationPurposeAuthority.kt`
- `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/LocationAcceptedReceiptIntegrity.kt`
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/location/ProtectedLocationCanonicalHandoff.kt`
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/location/ProtectedLocationAmbientCanonicalWriter.kt`
- `core/base/src/test/java/com/adsamcik/tracker/shared/base/database/data/LocationAcceptedReceiptIntegrityTest.kt`
- `tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/location/ProtectedLocationCanonicalHandoffTest.kt`
- `tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/location/LocationWalQualificationAdapterTest.kt`

Only the two new retained-integrity assertions in the ambient writer scenario are
selected from `LocationWalQualificationAdapterTest.kt`. Its other preserved draft
changes are excluded from this commit. The four root migration/preflight companions
are `RadioObservationChildMigration.kt`, `DevelopmentV28DatabaseContainment.kt`
and their corresponding tests in `core/base`.

## Static review

Root independently traced the fully validated Location preview, bounded point-ID
index, original import epoch, 256-point atomic batches, archive ordinal associations,
exact replay and source-owned lookup. Duplicate points and retention skips retain
their declared membership; deleting a point leaves a missing association, and replay
cannot reconnect it to an unrelated replacement. The six Room reader scenarios and
partial-prefix/retry/full-clear import sources use real DAOs and transactions.

Root also reviewed the qualified capture and background writer functions. Each
seals the existing receipt tuple and its independent purpose inside the existing
transaction. Exact old blank rows remain blank on replay. Canonical content and
purpose association checks are retained. Mutated tuple/purpose, cross-binding,
legacy compatibility and real capture/background writer assertions are authored.

`/root/completion_wiring_0930` independently accepted the root SQL/default/index/FK
definitions and the v29-only preflight and migration test sources. Historical v28
fixtures remain unchanged. Migration never invents origin for existing rows.

The codec/stager alignment reuses the actual registered Location and radio format
IDs; it changes no transfer scope. Original-job epoch propagation was independently
reviewed by `/root/completion_requirements_0930` in its separate receipt.

## Boundaries

No origin filter is added to ordinary Location backup or file import. Older points
remain data with honest labels. Files never grant recording permission or start a
provider. Pending native Location Replace still refuses replacement; bundle caller,
selector metadata, user-facing session export, paired map reader and history wiring
remain separate work. All test sources are uncompiled and unexecuted; no schema
generation/drift, device evidence, activation, convergence freeze or integration.
