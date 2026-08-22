package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityWithPolicies
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourcePolicyDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun ensureAuthority(entity: SourcePolicyAuthorityEntity): Long

	@Query("SELECT * FROM source_policy_authority WHERE id = 1")
	suspend fun authority(): SourcePolicyAuthorityEntity?

	@Query("SELECT * FROM source_policy_authority WHERE id = 1")
	fun observeAuthority(): Flow<SourcePolicyAuthorityEntity?>

	@Transaction
	@Query("SELECT * FROM source_policy_authority WHERE id = 1")
	fun observeAuthorityWithPolicies(): Flow<SourcePolicyAuthorityWithPolicies?>

	@Query(
		"SELECT policy.* FROM source_policy AS policy " +
			"INNER JOIN source_policy_authority AS authority " +
			"ON policy.policy_revision = authority.current_policy_revision " +
			"WHERE authority.id = 1 ORDER BY policy.source_kind",
	)
	suspend fun currentPolicies(): List<SourcePolicyEntity>

	@Query(
		"SELECT policy.* FROM source_policy AS policy " +
			"INNER JOIN source_policy_authority AS authority " +
			"ON policy.policy_revision = authority.current_policy_revision " +
			"WHERE authority.id = 1 ORDER BY policy.source_kind",
	)
	fun observeCurrentPolicies(): Flow<List<SourcePolicyEntity>>

	@Query(
		"SELECT * FROM source_policy WHERE policy_revision = :revision ORDER BY source_kind",
	)
	suspend fun policiesAtRevision(revision: Long): List<SourcePolicyEntity>

	@Query(
		"SELECT * FROM source_policy WHERE policy_revision = :revision AND source_kind = :sourceKind LIMIT 1",
	)
	suspend fun policyAtRevision(revision: Long, sourceKind: Int): SourcePolicyEntity?

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertPolicies(entities: List<SourcePolicyEntity>)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertConsentEpochs(entities: List<SourceConsentEpochEntity>): List<Long>

	@Query(
		"SELECT * FROM source_consent_epoch " +
			"WHERE source_kind = :sourceKind AND purpose = :purpose " +
			"ORDER BY epoch DESC LIMIT 1",
	)
	suspend fun latestConsentEpoch(sourceKind: Int, purpose: String): SourceConsentEpochEntity?

	@Query(
		"SELECT * FROM source_consent_epoch " +
			"WHERE source_kind = :sourceKind AND purpose = :purpose AND epoch = :epoch LIMIT 1",
	)
	suspend fun consentEpoch(sourceKind: Int, purpose: String, epoch: Long): SourceConsentEpochEntity?

	@Query(
		"SELECT * FROM source_consent_epoch " +
			"WHERE source_kind = :sourceKind AND purpose = :purpose ORDER BY epoch",
	)
	suspend fun consentHistory(sourceKind: Int, purpose: String): List<SourceConsentEpochEntity>

	@Query(
		"UPDATE source_policy_authority SET bootstrap_state = :bootstrapState, " +
			"current_policy_revision = :newRevision, " +
			"legacy_settings_fingerprint = :legacySettingsFingerprint, " +
			"updated_at_ms = :updatedAtMs " +
			"WHERE id = 1 AND bootstrap_state = :expectedBootstrapState " +
			"AND current_policy_revision = :expectedRevision",
	)
	suspend fun compareAndSetAuthority(
		expectedBootstrapState: String,
		expectedRevision: Long,
		bootstrapState: String,
		newRevision: Long,
		legacySettingsFingerprint: String?,
		updatedAtMs: Long,
	): Int
}
