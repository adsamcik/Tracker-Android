package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Dormant additive v28 delta for the final imported-Activity retention receipt.
 *
 * Activate this only with the statically accepted entity/source commit. Defaults deliberately
 * preserve existing retained rows as temporal-authority unavailable without rewriting their
 * legacy effect checksum.
 */
internal fun addImportedActivityRetentionTemporalAuthorityColumns(
	database: SupportSQLiteDatabase,
) {
	database.execSQL(
		"ALTER TABLE imported_activity_retention_receipt ADD COLUMN " +
			"temporal_authority_state TEXT NOT NULL DEFAULT 'UNAVAILABLE'",
	)
	database.execSQL(
		"ALTER TABLE imported_activity_retention_receipt ADD COLUMN " +
			"latest_member_start_time_ms INTEGER",
	)
	database.execSQL(
		"ALTER TABLE imported_activity_retention_receipt ADD COLUMN " +
			"latest_member_identity TEXT",
	)
	database.execSQL(
		"ALTER TABLE imported_activity_retention_receipt ADD COLUMN " +
			"structural_zone_range_count INTEGER NOT NULL DEFAULT 0",
	)
	database.execSQL(
		"ALTER TABLE imported_activity_retention_receipt ADD COLUMN " +
			"structural_zone_ranges_payload TEXT NOT NULL DEFAULT ''",
	)
	database.execSQL(
		"ALTER TABLE imported_activity_retention_receipt ADD COLUMN " +
			"structural_zone_coverage_complete INTEGER NOT NULL DEFAULT 0",
	)
}
