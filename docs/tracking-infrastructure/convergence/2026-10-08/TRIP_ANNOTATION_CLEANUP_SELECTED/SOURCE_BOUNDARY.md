# Existing trip deletion: organization cleanup

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Three Room methods authored, all NOT_RUN.

The existing TripPresentationRepository.deleteTrip -> DefaultTripRepository -> TripDao.deleteById chain removes a saved session_segment but currently leaves its migrated or edited segment annotation. That annotation retains the original name/activity label and can remain independently visible through OrganizationAnnotationDao.visible after its parent is gone.

This routine completion of approved migration/data cleanup keeps the existing deleteTrip and DAO signatures. One existing Room transaction deletes only the exact segment adapter annotation and segment row. Independent free/retrospective and logical adapters are preserved; no time-range, name, source or nearby-ID deletion is inferred. Existing data and foreign-key constraints still decide whether deleting the segment can complete. A failure rolls back both deletes. No new store, migration, UI, source authorization, sensor, automatic deletion, or deletion scope is added.

The actual Room test sources cover related label cleanup with siblings, exact-ID/idempotent orphan repair, and a constructed database trigger failure after label cleanup to check transactional rollback. The last case is a fixture storage fault, not a real device/storage result. All original DAO methods and tests otherwise remain unchanged. Full source deletion, retention, logical-session organization and T18/T25 remain open.
