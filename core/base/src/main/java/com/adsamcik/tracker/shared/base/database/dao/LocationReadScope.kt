package com.adsamcik.tracker.shared.base.database.dao

/** Fixed SQL fragments: callers cannot supply identifiers or authority predicates. */
object LocationReadScope {
	/** Exact current imported membership can survive independently of an erased native contribution. */
	const val HISTORY_CURRENT_IMPORT_COPY = """EXISTS (SELECT 1 FROM location_file_archive_point m
		JOIN location_file_archive a ON a.identity = m.archive_identity
		JOIN location_file_identity i ON i.portable_id = m.portable_id AND i.sample_id = m.sample_id
		JOIN source_evidence_state e ON e.id = 1 AND e.collected_data_epoch = a.collected_data_epoch
		WHERE m.sample_id = location_sample.id)"""
	/** Conservative discovery; source-owned Kotlin authenticates any receipt-based erase decision. */
	const val HISTORY_RETAINED = """(NOT EXISTS (SELECT 1 FROM source_evidence_state e WHERE e.id = 1
		AND e.retained_from_ms IS NOT NULL AND location_sample.time_ms < e.retained_from_ms))
		AND NOT EXISTS (SELECT 1 FROM collected_data_deletion_operation WHERE phase IN
		('RETENTION_PREPARED', 'RETENTION_DATASTORE_ACKNOWLEDGED', 'RETENTION_ROOM_GUARD_COMMITTED',
		'RETENTION_AUTHORITY_REISSUED', 'RETENTION_PROVIDER_RECONCILED', 'RETENTION_SOURCE_MAINTENANCE_COMPLETED'))"""
	const val CAPTURE_OR_LEGACY = "(NOT EXISTS (SELECT 1 FROM location_accepted_receipt lr WHERE lr.sample_id = location_sample.id) OR EXISTS (SELECT 1 FROM location_sample_purpose_authority lp WHERE lp.sample_id = location_sample.id AND lp.purpose = 'SESSION_CAPTURE'))"
	// Historical visibility follows the exact grant saved with each point. A later
	// source toggle or live-retention revoke stops new acquisition, not old reads.
	const val AMBIENT = """EXISTS (
		SELECT 1 FROM location_accepted_receipt lr
		JOIN location_sample_purpose_authority lp ON lp.sample_id = lr.sample_id
		JOIN source_evidence_state e ON e.id = 1 AND e.collected_data_epoch = lr.collected_data_epoch
		JOIN source_policy p ON p.policy_revision = lp.source_policy_revision AND p.source_kind = 1
		JOIN source_consent_epoch c ON c.source_kind = 1 AND c.purpose = 'AMBIENT_PRODUCT' AND c.epoch = lp.consent_epoch
		JOIN ambient_location_retention_authority r ON r.scope = 'LIVE_AMBIENT'
			AND r.approval_revision = lp.retention_approval_revision
		WHERE lr.sample_id = location_sample.id AND lp.purpose = 'AMBIENT_PRODUCT'
		AND p.ambient_persistence_eligible = 1 AND p.ambient_consent_epoch = lp.consent_epoch
		AND c.eligible = 1 AND c.persistence_eligible = 1
		AND r.state = 'ACTIVE' AND r.collected_data_epoch = lr.collected_data_epoch
		AND r.source_policy_revision = lp.source_policy_revision
		AND r.ambient_consent_epoch = lp.consent_epoch AND r.opaque_policy_id = lp.retention_policy_id
		AND (e.retained_from_ms IS NULL OR lr.observed_wall_time_ms - lr.wall_time_uncertainty_ms >= e.retained_from_ms)
		AND lr.source_admission_ordinal > e.deleted_source_event_high_water_ordinal
		AND NOT EXISTS (SELECT 1 FROM collected_data_deletion_operation WHERE phase IN ('RETENTION_PREPARED', 'RETENTION_DATASTORE_ACKNOWLEDGED', 'RETENTION_ROOM_GUARD_COMMITTED', 'RETENTION_AUTHORITY_REISSUED', 'RETENTION_PROVIDER_RECONCILED', 'RETENTION_SOURCE_MAINTENANCE_COMPLETED'))
	)"""
	const val WITH_OPTIONAL_AMBIENT = "(" + CAPTURE_OR_LEGACY + " OR (:includeAmbient AND " + AMBIENT + "))"
	const val ALL_VISIBLE = "(" + CAPTURE_OR_LEGACY + " OR " + AMBIENT + ")"
}
