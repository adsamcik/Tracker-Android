package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.data.LocationFileIdentityEntity
import java.util.UUID

@Dao
interface LocationFileIdentityDao {
	@Query("SELECT * FROM location_file_identity WHERE portable_id = :portableId")
	suspend fun byPortableId(portableId: String): LocationFileIdentityEntity?

	@Query("SELECT * FROM location_file_identity WHERE sample_id = :sampleId")
	suspend fun bySampleId(sampleId: Long): LocationFileIdentityEntity?

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertIfAbsent(value: LocationFileIdentityEntity): Long

	/** Assign once; another export or an overlapping import always reads the saved ID. */
	@Transaction
	suspend fun localIdentityFor(sampleId: Long): LocationFileIdentityEntity {
		require(sampleId > 0L)
		bySampleId(sampleId)?.let { return it }
		repeat(3) {
			val candidate = LocationFileIdentityEntity(
				portableId = UUID.randomUUID().toString(),
				sampleId = sampleId,
				imported = false,
				reportedOrigin = "unknown",
			)
			if (insertIfAbsent(candidate) != -1L) return candidate
			bySampleId(sampleId)?.let { return it }
		}
		error("Could not assign a Location file ID")
	}
}
