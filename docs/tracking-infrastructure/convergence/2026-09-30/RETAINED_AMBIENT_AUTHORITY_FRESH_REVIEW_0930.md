# Independent retained ambient authority review

**IMPLEMENTED_UNVALIDATED**, 2026-09-30. Review-only inspection by
`/root/fresh_bundle_context_review_0930` of the seven production and six
test paths in `RETAINED_AMBIENT_AUTHORITY_SOURCE_SLICE.md`, their existing
authority/maintenance dependencies and the shared map caller. No production
edits, Git mutations, builds, tests, schema generation, device checks or
activation were performed.

## Findings

1. **Current retention floor must apply to effective correction products.**
   The initial imported reader applied the current floor to every ancestor and
   then invalidated the entire identity. This contradicted the existing prune
   contract, which keeps a newer effective revision and its complete older
   lineage. The actual fixture in `RoomAmbientRadioPortableTransferTest.kt:403`
   retains revision 1 at 1000 and revision 2 at 2000 after a floor of 1500.
   The coordinator's revised `RetainedImportedAmbientRadioReader.kt:90-104`
   now applies that floor to the effective revision while still requiring each
   ancestor's original receipt/approval bounds, checksum, epoch and lack of
   explicit erase footprints. That production correction is coherent in source.

   **The native analogue remains in the reviewed source:**
   `RetainedAmbientRadioReader.kt:66-71,147-151` applies the current floor to
   every ancestor/dependency. Native pruning also selects expiry using the
   latest revision (`AmbientWifiFactDao.kt:418-430`; corresponding Cell query).
   Align native filtering with the retained selected fact and selected aggregate
   owner, retaining full historical authentication and explicit erase checks.
   Add ordinary/day/paired source assertions for a retained corrected product.

2. **Qualified parents do not bound exposed child observation times.**
   `RoomAmbientWifiRepository.kt:200-216,345-360` and
   `RoomAmbientCellRepository.kt:203-214,281-295` check ownership, checksum
   and count, but do not require each child’s measured interval to lie within
   its qualified parent’s measured bounds. API fact constructors only constrain
   child counts. A structurally valid, recomputed-checksum child outside the
   parent interval can therefore escape the parent’s retained-data qualification.
   The file/import attachment path already checks this relationship; retained
   reads should check it too for native and imported children. Use the parent’s
   measured lower/upper bounds, including uncertainty and the approved rounding
   behavior. Add real Room cases where an earlier/later child retains its correct
   count and recomputed checksum but lies outside those bounds.

3. **Authority dependency limits are reported as unavailable storage/authority.**
   `RetainedAmbientRadioAuthority.kt:79,86,89,91` returns `null` for its bounded
   generation, authorization and demand limits. The new ordinary reader turns
   that `null` into `SOURCE_AUTHORITY_UNAVAILABLE`
   (`RetainedAmbientRadioReader.kt:39-41,120-122`), and its strict public wrapper
   reports `StorageUnavailable`. Other bounds correctly use `DEPENDENCY_LIMIT`
   and `DependencyOverflow`. Preserve an explicit limit outcome from the shared
   authority loader so a large valid history is not mislabeled as a storage or
   provenance failure. Test an authority-dependency limit, not only the already
   authored fact-discovery limit.

## Traced behaviors that are coherent

- Ordinary range/day reads and optional maps share the same source-owned
  native/imported qualification under their caller’s Room snapshot. Public
  calls use the existing injected IO dispatcher.
- Current Off choices affect availability, not the historical recording grant.
  Historical source policy/consent/demand/provider registration and authorization
  are consulted. The current boot is injected only for an open registration;
  finite historical registrations retain their independent interval authority.
- Native cursor, full contiguous lineage, aggregate-owner compatibility,
  retention approval, epoch/high-water and authenticated erase footprints are
  checked. An unchanged reference does not independently revive an erased owner.
- Acquisition gaps use their own historical consent/policy/retention tuple and
  checksum. They do not claim a provider observation or require invented provider
  evidence.
- Imported records require their real archive import receipt, current data epoch,
  original portable retention grant and no archive/identity/effect replay fence.
  No current native sensor permission or source choice is required for import.
- The qualified paired seam retains explicit ambient gaps and independently
  qualified capture/other-source data. It introduces no collection request,
  Location demand, network access, migration, backup restriction or new UI copy.
- The authored tests use real Room rows/import transactions and receipts for
  Off history, missing grants/receipts, stale epoch, floor, tombstone/footprint,
  cursor alteration, open-registration boot and discovery limit scenarios.
  Their execution and Hilt/Room composition remain unverified.

## Additional bundle repair inspection

The coordinator's updated `SessionBundleMetadataImport.prepare` catches named
malformed-format exceptions while preserving ordinary transport/storage I/O.
Its new `temporary preparation read failure retries without reimporting the
durable child` test source uses the actual Location adapter, existing Room
receipts, failed preparation and subsequent successful exact membership.
That closes the earlier generic-I/O classification finding in static source.
Native Steps bundle duplicate membership remains an unresolved storage decision.

## Remaining boundaries

Exact overlapping radio archive membership and partial-map notice remain
pending their own approved slices. This review does not accept full source
lifecycle, backup/bundle/history assembly or device readiness. No test result
or integration readiness is claimed.

## 2026-09-30 static repair recheck

The three findings above are **closed in the inspected production and test
sources**. Earlier findings remain as the review history. This closure is static
inspection only; all source remains **IMPLEMENTED_UNVALIDATED**. No commands
compiled or executed any authored test, provider, Room migration or UI.

1. `RetainedImportedAmbientRadioReader.kt:90–116` now applies the current
   retained floor to the greatest revision of each fact. Every ancestor still
   requires its original receipt and policy floor, valid content, current epoch
   and absence of explicit archive/identity/effect erase. The native reader's
   candidate filter (`RetainedAmbientRadioReader.kt:19–24,104–109`) and selected
   aggregate-owner checks (`:68–75,153–159`) apply the current floor to the
   effective selected products, while complete correction dependencies retain
   their historical authentication, original approval bounds and erase/epoch/
   high-water checks. The real Room native correction fixture in
   `RoomRetainedAmbientRadioContextTest.kt:137–178` checks ordinary range/day,
   paired map context and export of both correction revisions. The existing
   real imported prune fixture in `RoomAmbientRadioPortableTransferTest.kt:430–514`
   now also checks retained range/day and complete-lineage export. No ancestor
   scope is silently rewritten.

2. The overflow-safe `radioChildFitsMeasuredInterval` helper checks the whole
   child uncertainty interval against the already qualified parent's measured
   lower/upper bounds. All four ordinary native/imported Wi-Fi/Cell mapping
   branches call it (`RoomAmbientWifiRepository.kt:202–203,243–244`;
   `RoomAmbientCellRepository.kt:205–206,244–245`). The measured-bounds conversion
   companion subtracts native lower uncertainty exactly once; imported parents
   already store their measured bounds. Real Room fixtures retain complete child
   counts and recomputed authentic checksums while placing native or imported
   children outside those bounds; their assertions cover unavailability in
   retained reads and rejection before portable export output.

3. `RetainedAmbientRadioAuthority.kt:79,86–92` now throws the narrowly typed
   `RetainedRadioDependencyLimitExceeded` for generation, authorization, current
   registration and demand-budget excess. Both native qualification methods
   catch that exact type and preserve `DEPENDENCY_LIMIT`
   (`RetainedAmbientRadioReader.kt:39–42,124–127`). Both strict public wrappers
   then return the existing `DependencyOverflow` result, and the shared map
   caller retains the explicit limit gap. Missing/malformed authority still
   follows its separate unavailable path. `RetainedAmbientRadioAuthorityTest`
   has source fixtures for generation and authorization dependency limits.

No further concrete defect was found within these three repairs. Exact archive
deduplication membership, partial-map notice, full source assembly and the
deferred convergence/device gates remain outside this closure.
