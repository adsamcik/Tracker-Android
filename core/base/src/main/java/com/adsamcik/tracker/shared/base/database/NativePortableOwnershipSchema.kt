package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.NativePortableOwnership
import com.adsamcik.tracker.shared.base.database.data.NativePortableOwnershipEntity

/** Additive metadata lookup. Existing native payloads and their identity domains do not change. */
internal fun createNativePortableOwnershipTable(db: SupportSQLiteDatabase) {
	db.execSQL("""
		CREATE TABLE IF NOT EXISTS native_portable_ownership (
		  source_kind INTEGER NOT NULL, identity_kind TEXT NOT NULL,
		  opaque_identity TEXT NOT NULL, raw_identity TEXT NOT NULL,
		  logical_tracking_id TEXT NOT NULL, service_run_id TEXT NOT NULL,
		  PRIMARY KEY(source_kind, identity_kind, raw_identity, logical_tracking_id, service_run_id)
		)
	""".trimIndent())
	db.execSQL("CREATE INDEX IF NOT EXISTS idx_native_portable_owner_identity " +
		"ON native_portable_ownership(source_kind, opaque_identity, logical_tracking_id)")
	db.execSQL("CREATE INDEX IF NOT EXISTS idx_native_portable_owner_run " +
		"ON native_portable_ownership(logical_tracking_id, service_run_id)")
}

/** Complete rebuild, never a capped history probe. Called inside the migration transaction. */
internal fun rebuildNativePortableOwnership(db: SupportSQLiteDatabase) {
	db.execSQL("DELETE FROM native_portable_ownership")
	db.query("SELECT logical_tracking_id, service_run_id FROM source_service_run").use { cursor ->
		while (cursor.moveToNext()) {
			insertNativePortableOwners(db, NativePortableOwnership.run(cursor.getString(0), cursor.getString(1)))
		}
	}
	listOf(
		Triple("activity_captured_window_revision", "logical_window_id", NativePortableOwnership.ACTIVITY),
		Triple("pressure_fact_revision", "logical_fact_id", NativePortableOwnership.PRESSURE),
	).forEach { (table, window, source) ->
		db.query("SELECT DISTINCT logical_tracking_id, service_run_id, $window FROM $table").use { cursor ->
			while (cursor.moveToNext()) {
				insertNativePortableOwners(db, NativePortableOwnership.fact(
					source, cursor.getString(0), cursor.getString(1), cursor.getString(2),
				))
			}
		}
	}
}

private fun insertNativePortableOwners(db: SupportSQLiteDatabase, owners: List<NativePortableOwnershipEntity>) {
	owners.forEach { owner ->
		db.execSQL("INSERT OR IGNORE INTO native_portable_ownership " +
			"(source_kind, identity_kind, opaque_identity, raw_identity, logical_tracking_id, service_run_id) " +
			"VALUES (?, ?, ?, ?, ?, ?)", arrayOf(owner.sourceKind, owner.identityKind, owner.opaqueIdentity,
			owner.rawIdentity, owner.logicalTrackingId, owner.serviceRunId))
	}
}

/** Every source deletion path, including retention SQL, removes only unsupported lookup keys. */
internal fun installNativePortableOwnershipDeletionTriggers(db: SupportSQLiteDatabase) {
	listOf(
		Triple("activity_captured_window_revision", "logical_window_id", NativePortableOwnership.ACTIVITY),
		Triple("pressure_fact_revision", "logical_fact_id", NativePortableOwnership.PRESSURE),
	).forEach { (table, window, source) ->
		val kind = if (source == NativePortableOwnership.ACTIVITY) "CAPTURE_WINDOW" else "WINDOW"
		db.execSQL("""
			CREATE TRIGGER IF NOT EXISTS prune_native_portable_${source}_window
			AFTER DELETE ON $table BEGIN
			  DELETE FROM native_portable_ownership
			  WHERE source_kind = $source AND identity_kind = '$kind'
			    AND logical_tracking_id = OLD.logical_tracking_id AND service_run_id = OLD.service_run_id
			    AND raw_identity = OLD.$window
			    AND NOT EXISTS (SELECT 1 FROM $table remaining
			      WHERE remaining.logical_tracking_id = OLD.logical_tracking_id
			        AND remaining.service_run_id = OLD.service_run_id AND remaining.$window = OLD.$window);
			  ${pruneUnsupportedNativeRunOwners("OLD.logical_tracking_id", "OLD.service_run_id")}
			END
		""".trimIndent())
	}
	db.execSQL("""
		CREATE TRIGGER IF NOT EXISTS prune_native_portable_run_delete
		AFTER DELETE ON source_service_run BEGIN
		  ${pruneUnsupportedNativeRunOwners("OLD.logical_tracking_id", "OLD.service_run_id")}
		END
	""".trimIndent())
	db.execSQL("""
		CREATE TRIGGER IF NOT EXISTS prune_native_portable_run_update
		AFTER UPDATE OF logical_tracking_id, service_run_id ON source_service_run BEGIN
		  ${pruneUnsupportedNativeRunOwners("OLD.logical_tracking_id", "OLD.service_run_id")}
		END
	""".trimIndent())
}

private fun pruneUnsupportedNativeRunOwners(logical: String, run: String): String = """
	DELETE FROM native_portable_ownership
	WHERE logical_tracking_id = $logical AND service_run_id = $run
	  AND identity_kind IN ('LOGICAL_ENTRY', 'PHYSICAL_RUN', 'DELETION_SCOPE')
	  AND NOT EXISTS (SELECT 1 FROM source_service_run remaining
	    WHERE remaining.logical_tracking_id = $logical AND remaining.service_run_id = $run)
	  AND NOT EXISTS (SELECT 1 FROM activity_captured_window_revision remaining
	    WHERE native_portable_ownership.source_kind = 2
	      AND remaining.logical_tracking_id = $logical AND remaining.service_run_id = $run)
	  AND NOT EXISTS (SELECT 1 FROM pressure_fact_revision remaining
	    WHERE native_portable_ownership.source_kind = 4
	      AND remaining.logical_tracking_id = $logical AND remaining.service_run_id = $run);
""".trimIndent()
