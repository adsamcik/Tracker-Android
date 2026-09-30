# Organization source identity correction

Status: `IMPLEMENTED_UNVALIDATED`. Static source review and test-source authorship only; no gates executed.

The source-owned identities of captured Steps and Pressure entries and background Steps days
already use `sha256:` followed by 64 lowercase hexadecimal characters. Their organization selectors
now validate that exact form and retain the original identifier. Captured Activity, Wi-Fi and Cell
identities are raw 64-character digests, as are Location file and background radio archive identities.
No identities are stripped, rehashed or reassociated by date.

Static identity references: `StepsPortableFormatV1.kt` and `PressurePortableFormatV1.kt` in
`:core:model`, `ActivityCapturedPortableTransfer.kt` and `CellCapturedPortableTransfer.kt` in
`:core:base`, and `WifiCapturedPortableFormatV1.kt` in `:stats:api`. Their constructors and
imported database identities agree with these shapes.

The existing codec round-trip fixture now uses real Steps identity shapes. A focused fixture covers
both Steps selector kinds and captured Pressure, serialization without rewriting, raw/prefixed type rejection, uppercase,
short and double-prefix rejection, and invalid decoded selectors. The existing Room organization
edit fixture uses the same real day identity and continues to cover membership preservation after edits.

Exact source/test paths and SHA-256 values are in `ORGANIZATION_STEPS_IDENTITY_FILES.json`.
Compilation, test execution, convergence freeze and integration remain unavailable in this phase.
