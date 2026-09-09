package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsEntryEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsManifestEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsRunEntity

/**
 * Dormant source-local storage, not an import command. A future authoritative writer must own the
 * whole entry transaction and recheck integrity, destination ownership, retention and deletion.
 * ABORT preserves existing identity/content; conflicts must never silently overwrite a receipt.
 */
@Dao
interface ImportedStepsDao {
	/** Inserts original entry identity without replacing conflicting content. */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertEntry(entry: ImportedStepsEntryEntity)

	/** Inserts exact physical membership under an existing imported entry. */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertRun(run: ImportedStepsRunEntity)

	/** Inserts immutable foreign capture attribution under an existing imported run. */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertManifest(manifest: ImportedStepsManifestEntity)

	/** Reads the exact original portable entry identity, not a timestamp overlap. */
	@Query("SELECT * FROM imported_steps_entry WHERE identity = :identity")
	suspend fun entry(identity: String): ImportedStepsEntryEntity?

	/** Reads the exact original portable run identity and deletion-scope mapping. */
	@Query("SELECT * FROM imported_steps_run WHERE identity = :identity")
	suspend fun run(identity: String): ImportedStepsRunEntity?

	/** Reads immutable manifests in canonical revision order for one exact portable run. */
	@Query("SELECT * FROM imported_steps_manifest WHERE run_identity = :identity ORDER BY revision")
	suspend fun manifests(identity: String): List<ImportedStepsManifestEntity>

	/** Full collected-data clear only; cascades the foreign metadata, never replaces source fences. */
	@Query("DELETE FROM imported_steps_entry")
	fun deleteAll()
}
