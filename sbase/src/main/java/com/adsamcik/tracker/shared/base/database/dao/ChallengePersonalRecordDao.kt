package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ChallengePersonalRecordEntity
import com.adsamcik.tracker.shared.base.database.dao.BaseDao
import kotlinx.coroutines.flow.Flow

@Dao
interface ChallengePersonalRecordDao : BaseDao<ChallengePersonalRecordEntity> {
	@Query("SELECT * FROM challenge_personal_record WHERE challenge_type = :type AND metric = :metric")
	suspend fun get(type: String, metric: String): ChallengePersonalRecordEntity?

	@Query("SELECT * FROM challenge_personal_record ORDER BY achieved_at DESC")
	fun observeAll(): Flow<List<ChallengePersonalRecordEntity>>

	@Query("SELECT * FROM challenge_personal_record WHERE challenge_type = :type")
	suspend fun getByType(type: String): List<ChallengePersonalRecordEntity>
	@Query("DELETE FROM challenge_personal_record")
	fun deleteAll()

}
