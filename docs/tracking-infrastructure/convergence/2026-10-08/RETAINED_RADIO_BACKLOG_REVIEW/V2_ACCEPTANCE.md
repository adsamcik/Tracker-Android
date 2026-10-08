# Retained radio backlog V2: bounded source acceptance

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Independent source review only.
All four authored methods remain NOT_RUN. No build, compilation, tests, schema, lint,
diff-check, device/provider activation, CI, Git mutation or integration was executed.

## Exact reviewed input

- Five selected LF source paths at base/current 3b4600dd530fb7f4161d7e628d00f0d8c96c1dc4.
- FILES 8f6fcb21681b0dc4fd9c52b98ca773192780966e07ec08856e5b1a7f26472bf9.
- Patch 966a37eb22ae4ff18b093c733a908a4ff7d3a2209fe7b76a27633524548071d4.
- Eleven dependencies unchanged: d3e4440cd5be639c70590dfdbeb59bfc3250e5e9c58f78b0b1ca280edbc52b59.

All five BASE/SELECTED/raw WORKING_BEFORE hashes match the manifest; eleven dependency
snapshots/current committed inputs match exactly. Both production adapters are byte-identical
to V1. Frozen V1 and its findings are preserved; this recheck does not erase that evidence.

## Concrete repairs rechecked

1. Wi-Fi's negative now requires exactly two Groups results before checking Rejected.
   Its actual accepted Start actions are present and inspected by the adapter's original
   action/member generation check. The assertion no longer accepts an empty Groups result.
2. Cell's negative uses the real source_demand.lifecycle_lease_generation column and
   SESSION_CAPTURE rows created by installHistoricalCellGroups. Original source_authorization
   is untouched. The actual qualifyCellPurposeItems/readRadioPurposeItems/RadioPurposeItemQualifier
   chain reconstructs original authorization from those changed demand rows and refuses the
   mismatch before group classification. The test requires the concrete top-level
   AUTHORIZATION_MISMATCH result and zero saved captured facts. Its name now describes the
   actual authorized-demand seam. No action proof or new production requirement was added.
3. HistoricalSavedSourceTestFixtures.kt and both callers use the renamed
   installOriginalSourceStopFixture/advanceRetainedSourceOperations consistently. Aside from
   these names, the immutable Stop checksum/ACTIVE predecessor and constructed later mutable
   operation fields are unchanged. No current committed duplicate helper exists.

The two positive actual Room ingress -> adapter -> writer/lane cases are otherwise unchanged
from the V1 source review. No captured facts are seeded; original individual child details and
WAL content/identity remain asserted. The original generation/manifest clock, finite original
Stop, provider/member/policy/consent, retention/epoch/deletion and current replacement fences
remain as reviewed. This is bounded reuse of existing retained-data helpers, with no storage,
provider, architecture or consent addition.

## Result and limits

No remaining concrete source blocker in these exact V2 bytes. Accept for the two retained
radio backlog adapters and their authored source cases. The tests construct lifecycle operations;
they do not execute a real coordinator quality/End/reboot or hardware callback. Shared final
binding writers/models/lanes, other clock/cap gaps, full source-to-product/device evidence,
convergence gates, all25 full-chain prerequisites and T25 remain open.
