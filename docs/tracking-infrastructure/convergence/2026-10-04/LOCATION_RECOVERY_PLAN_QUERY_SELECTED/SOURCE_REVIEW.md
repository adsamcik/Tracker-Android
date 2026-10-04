# Independent source review: passive Location recovery query

Result: accepted for this narrow source selection, IMPLEMENTED_UNVALIDATED. No validation command or provider activation was performed. This is not passive Location readiness or assembly completion.

## Exact input

- Parent: `35c3bf7c8d57ecddea437c679549b4f68cd3be0d`.
- FILES.json: `ac951c06cb3fabd19ebea659611c30347e2ddd42ef9dc1bf41c5904884bd13f4`.
- SELECTION.patch: `7f02d256b648e5c4b0bb9bd0e683df9a0aa8854fe4b029b86bc3badd8898534f`.
- SOURCE_BOUNDARY.md: `706c483d515f952654bd93264212e99ee8bbc2408815cef2d75a93804ad08661`.

The manifest hashes match all eight BASE/WORKING_BEFORE/WORKING_AFTER/SELECTED snapshots. The selected patch adds only the existing four-line DAO query, its test import, and one test case. No schema or broader held draft is selected.

## Source assessment

Committed SourceRegistrationRepository.abandonUnplannedSystemLocationReservation (252–257) and passiveLocationIdentity (267–291) already call latestRegistrationPlan, absent from the committed DAO. The query completes that existing dependency: it limits selection to the exact generation and chooses its highest authorization revision from the existing primary-key table. Recovery still checks source instance, physical fingerprint, integrity, supported payload version, canonical plan bytes, and passive system delivery. An invalid latest record is refused; the reader does not borrow an older valid record or abandon a planned reservation.

The new SourceRegistrationRepositoryTest uses the existing real Room database and passive registration fixture. It covers no plan, another generation, configuration ordering independent of authorization ordering, highest authorization, positive handle reconstruction, protection of a planned RESERVED row, and refusal of unsupported latest metadata without fallback. The unsupported-version entity is structurally valid under the existing entity constructor, so this exercises the recovery reader rather than failing during fixture creation. The case is authored, NOT_RUN.

No concrete source blocker remains in this two-path selection. Current passive transport transitions, removal failure/retry, startup and Off behavior still require their separate full-chain source and eventual device evidence; all 25 assembly prerequisites remain open.
