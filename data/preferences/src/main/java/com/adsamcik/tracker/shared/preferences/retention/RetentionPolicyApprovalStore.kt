package com.adsamcik.tracker.shared.preferences.retention

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesDataStore
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

enum class RetentionPolicyApprovalStatus {
	UNAPPROVED,
	PENDING,
	APPROVED,
	INVALID,
}

data class ApprovedRetentionPolicy(
	val configurationGeneration: Long,
	val revision: Long,
	val opaquePolicyId: String,
	val configurationChecksum: String,
	val integrityChecksum: String,
) {
	init {
		require(configurationGeneration > 0L)
		require(revision > 0L)
		require(opaquePolicyId.isNotBlank() && opaquePolicyId.length <= 256)
		require(DIGEST.matches(configurationChecksum))
		require(DIGEST.matches(integrityChecksum))
	}

	private companion object {
		val DIGEST = Regex("[0-9a-f]{64}")
	}
}

internal fun ApprovedRetentionPolicy.hasAuthenticChecksum(
	status: RetentionPolicyApprovalStatus,
): Boolean = integrityChecksum == RetentionPolicyApprovalIntegrity.checksum(
	status = status,
	configurationGeneration = configurationGeneration,
	revision = revision,
	opaquePolicyId = opaquePolicyId,
	configurationChecksum = configurationChecksum,
)

internal fun ApprovedRetentionPolicy.hasSameStableIdentity(
	other: ApprovedRetentionPolicy,
): Boolean =
	configurationGeneration == other.configurationGeneration &&
		revision == other.revision &&
		opaquePolicyId == other.opaquePolicyId &&
		configurationChecksum == other.configurationChecksum

internal fun ApprovedRetentionPolicy.approvalMutationIdentity(): String =
	"retention-approval-v1:$configurationGeneration:$revision:$opaquePolicyId:$configurationChecksum"

data class RetentionPolicyStage(
	val policy: ApprovedRetentionPolicy,
	val approvalRequired: Boolean,
)

sealed interface ApprovedRetentionPolicyRead {
	data class Available(val policy: ApprovedRetentionPolicy) : ApprovedRetentionPolicyRead
	data class Unavailable(val reason: ApprovedRetentionPolicyUnavailableReason) :
		ApprovedRetentionPolicyRead
}

sealed interface RetentionPolicyCandidateRead {
	data class Available(
		val policy: ApprovedRetentionPolicy,
		val status: RetentionPolicyApprovalStatus,
	) : RetentionPolicyCandidateRead

	data class Unavailable(val reason: ApprovedRetentionPolicyUnavailableReason) :
		RetentionPolicyCandidateRead
}

enum class ApprovedRetentionPolicyUnavailableReason {
	NOT_APPROVED,
	PENDING_APPROVAL,
	CONFIGURATION_CHANGED,
	INTEGRITY_MISMATCH,
}

internal sealed interface StoredRetentionPolicyApproval {
	data object Missing : StoredRetentionPolicyApproval
	data object Invalid : StoredRetentionPolicyApproval
	data class Present(val record: RetentionPolicyApprovalRecord) :
		StoredRetentionPolicyApproval
}

private val Context.retentionPolicyApprovalDataStore: DataStore<Preferences> by preferencesDataStore(
	name = "retention_policy_approval",
)

internal class RetentionPolicyApprovalStore(
	context: Context,
	private val ioDispatcher: CoroutineDispatcher,
	private val nextOpaquePolicyId: () -> String = { UUID.randomUUID().toString() },
) {
	private val dataStore = context.applicationContext.retentionPolicyApprovalDataStore

	val records: Flow<StoredRetentionPolicyApproval> = dataStore.data.map { preferences ->
		val record = preferences.toRecordOrNull()
		when {
			record != null -> StoredRetentionPolicyApproval.Present(record)
			preferences.hasAnyApprovalValue() -> StoredRetentionPolicyApproval.Invalid
			else -> StoredRetentionPolicyApproval.Missing
		}
	}

	suspend fun stage(configuration: RetentionConfigState): RetentionPolicyStage =
		withContext(ioDispatcher) {
			val configurationChecksum = RetentionPolicyApprovalIntegrity.configurationChecksum(
				configuration,
			)
			var stage: RetentionPolicyStage? = null
			dataStore.edit { preferences ->
				val current = preferences.toRecordOrNull()
					?.takeIf(RetentionPolicyApprovalRecord::isAuthentic)
					?.takeIf { it.policy.configurationChecksum == configurationChecksum }
				if (current != null && current.status in setOf(
						RetentionPolicyApprovalStatus.PENDING,
						RetentionPolicyApprovalStatus.APPROVED,
					)
				) {
					stage = RetentionPolicyStage(
						current.policy,
						current.status == RetentionPolicyApprovalStatus.PENDING,
					)
					return@edit
				}
				val storedGeneration = preferences[GENERATION_KEY] ?: 0L
				val storedRevision = preferences[REVISION_KEY] ?: 0L
				check(storedGeneration in 0 until Long.MAX_VALUE) {
					"Retention configuration generation is exhausted or invalid"
				}
				check(storedRevision in 0 until Long.MAX_VALUE) {
					"Retention policy revision is exhausted or invalid"
				}
				val generation = storedGeneration + 1L
				val revision = storedRevision + 1L
				val opaquePolicyId = nextOpaquePolicyId()
				require(opaquePolicyId.isNotBlank() && opaquePolicyId.length <= 256)
				val pending = RetentionPolicyApprovalRecord.create(
					status = RetentionPolicyApprovalStatus.PENDING,
					configurationGeneration = generation,
					revision = revision,
					opaquePolicyId = opaquePolicyId,
					configurationChecksum = configurationChecksum,
				)
				preferences.write(pending)
				stage = RetentionPolicyStage(pending.policy, approvalRequired = true)
			}
			checkNotNull(stage)
		}

	suspend fun current(
		configuration: RetentionConfigState,
	): ApprovedRetentionPolicyRead = withContext(ioDispatcher) {
		when (val candidate = candidate(configuration)) {
			is RetentionPolicyCandidateRead.Available ->
				if (candidate.status == RetentionPolicyApprovalStatus.APPROVED) {
					ApprovedRetentionPolicyRead.Available(candidate.policy)
				} else {
					ApprovedRetentionPolicyRead.Unavailable(
						ApprovedRetentionPolicyUnavailableReason.PENDING_APPROVAL,
					)
				}
			is RetentionPolicyCandidateRead.Unavailable ->
				ApprovedRetentionPolicyRead.Unavailable(candidate.reason)
		}
	}

	suspend fun candidate(
		configuration: RetentionConfigState,
	): RetentionPolicyCandidateRead = withContext(ioDispatcher) {
		val preferences = dataStore.data.first()
		val record = preferences.toRecordOrNull() ?: return@withContext run {
			val reason = if (preferences.hasAnyApprovalValue()) {
				ApprovedRetentionPolicyUnavailableReason.INTEGRITY_MISMATCH
			} else {
				ApprovedRetentionPolicyUnavailableReason.NOT_APPROVED
			}
			RetentionPolicyCandidateRead.Unavailable(reason)
		}
		if (!record.isAuthentic()) {
			return@withContext RetentionPolicyCandidateRead.Unavailable(
				ApprovedRetentionPolicyUnavailableReason.INTEGRITY_MISMATCH,
			)
		}
		if (
			record.policy.configurationChecksum !=
			RetentionPolicyApprovalIntegrity.configurationChecksum(configuration)
		) {
			return@withContext RetentionPolicyCandidateRead.Unavailable(
				ApprovedRetentionPolicyUnavailableReason.CONFIGURATION_CHANGED,
			)
		}
		if (record.status !in setOf(
				RetentionPolicyApprovalStatus.PENDING,
				RetentionPolicyApprovalStatus.APPROVED,
			)
		) {
			return@withContext RetentionPolicyCandidateRead.Unavailable(
				ApprovedRetentionPolicyUnavailableReason.NOT_APPROVED,
			)
		}
		RetentionPolicyCandidateRead.Available(record.policy, record.status)
	}

	suspend fun pending(): RetentionPolicyCandidateRead = withContext(ioDispatcher) {
		val preferences = dataStore.data.first()
		val record = preferences.toRecordOrNull()
			?: return@withContext RetentionPolicyCandidateRead.Unavailable(
				ApprovedRetentionPolicyUnavailableReason.NOT_APPROVED,
			)
		if (!record.isAuthentic()) {
			return@withContext RetentionPolicyCandidateRead.Unavailable(
				ApprovedRetentionPolicyUnavailableReason.INTEGRITY_MISMATCH,
			)
		}
		if (record.status != RetentionPolicyApprovalStatus.PENDING) {
			return@withContext RetentionPolicyCandidateRead.Unavailable(
				ApprovedRetentionPolicyUnavailableReason.NOT_APPROVED,
			)
		}
		RetentionPolicyCandidateRead.Available(record.policy, record.status)
	}

	suspend fun markApproved(expected: ApprovedRetentionPolicy): ApprovedRetentionPolicy? =
		withContext(ioDispatcher) {
			var approved: ApprovedRetentionPolicy? = null
			dataStore.edit { preferences ->
				val current = preferences.toRecordOrNull()
				if (current == null ||
					!current.isAuthentic() ||
					current.status != RetentionPolicyApprovalStatus.PENDING ||
					!expected.hasAuthenticChecksum(RetentionPolicyApprovalStatus.PENDING) ||
					!current.policy.hasSameStableIdentity(expected)
				) {
					return@edit
				}
				val value = RetentionPolicyApprovalRecord.create(
						status = RetentionPolicyApprovalStatus.APPROVED,
						configurationGeneration = expected.configurationGeneration,
						revision = expected.revision,
						opaquePolicyId = expected.opaquePolicyId,
						configurationChecksum = expected.configurationChecksum,
					)
				preferences.write(value)
				approved = value.policy
			}
			approved
		}

	suspend fun resetForTests() {
		dataStore.edit { it.clear() }
	}

	private companion object {
		val GENERATION_KEY = longPreferencesKey("configuration_generation")
		val REVISION_KEY = longPreferencesKey("revision")
		val STATE_KEY = stringPreferencesKey("state")
		val OPAQUE_POLICY_ID_KEY = stringPreferencesKey("opaque_policy_id")
		val CONFIGURATION_CHECKSUM_KEY = stringPreferencesKey("configuration_checksum")
		val INTEGRITY_CHECKSUM_KEY = stringPreferencesKey("integrity_checksum")

		fun Preferences.toRecordOrNull(): RetentionPolicyApprovalRecord? {
			val generation = this[GENERATION_KEY] ?: return null
			val revision = this[REVISION_KEY] ?: return null
			val state = this[STATE_KEY] ?: return null
			val opaquePolicyId = this[OPAQUE_POLICY_ID_KEY] ?: return null
			val configurationChecksum = this[CONFIGURATION_CHECKSUM_KEY] ?: return null
			val integrityChecksum = this[INTEGRITY_CHECKSUM_KEY] ?: return null
			return runCatching {
				RetentionPolicyApprovalRecord(
					status = RetentionPolicyApprovalStatus.valueOf(state),
					policy = ApprovedRetentionPolicy(
						configurationGeneration = generation,
						revision = revision,
						opaquePolicyId = opaquePolicyId,
						configurationChecksum = configurationChecksum,
						integrityChecksum = integrityChecksum,
					),
				)
			}.getOrNull()
		}

		fun Preferences.hasAnyApprovalValue(): Boolean =
			this[GENERATION_KEY] != null ||
				this[REVISION_KEY] != null ||
				this[STATE_KEY] != null ||
				this[OPAQUE_POLICY_ID_KEY] != null ||
				this[CONFIGURATION_CHECKSUM_KEY] != null ||
				this[INTEGRITY_CHECKSUM_KEY] != null

		fun Preferences.write(value: RetentionPolicyApprovalRecord) {
			this[GENERATION_KEY] = value.policy.configurationGeneration
			this[REVISION_KEY] = value.policy.revision
			this[STATE_KEY] = value.status.name
			this[OPAQUE_POLICY_ID_KEY] = value.policy.opaquePolicyId
			this[CONFIGURATION_CHECKSUM_KEY] = value.policy.configurationChecksum
			this[INTEGRITY_CHECKSUM_KEY] = value.policy.integrityChecksum
		}
	}
}

internal data class RetentionPolicyApprovalRecord(
	val status: RetentionPolicyApprovalStatus,
	val policy: ApprovedRetentionPolicy,
) {
	fun isAuthentic(): Boolean = policy.hasAuthenticChecksum(status)

	fun statusFor(configuration: RetentionConfigState): RetentionPolicyApprovalStatus = when {
		!isAuthentic() -> RetentionPolicyApprovalStatus.INVALID
		status == RetentionPolicyApprovalStatus.PENDING ->
			RetentionPolicyApprovalStatus.PENDING
		policy.configurationChecksum !=
			RetentionPolicyApprovalIntegrity.configurationChecksum(configuration) ->
			RetentionPolicyApprovalStatus.INVALID
		else -> status
	}

	companion object {
		fun create(
			status: RetentionPolicyApprovalStatus,
			configurationGeneration: Long,
			revision: Long,
			opaquePolicyId: String,
			configurationChecksum: String,
		): RetentionPolicyApprovalRecord = RetentionPolicyApprovalRecord(
			status = status,
			policy = ApprovedRetentionPolicy(
				configurationGeneration = configurationGeneration,
				revision = revision,
				opaquePolicyId = opaquePolicyId,
				configurationChecksum = configurationChecksum,
				integrityChecksum = RetentionPolicyApprovalIntegrity.checksum(
					status,
					configurationGeneration,
					revision,
					opaquePolicyId,
					configurationChecksum,
				),
			),
		)
	}
}

internal object RetentionPolicyApprovalIntegrity {
	fun configurationChecksum(configuration: RetentionConfigState): String = digest(
		"retention-configuration-v1",
		configuration.rawDataRetentionDays,
		configuration.wifiCellRetentionDays,
		configuration.tripRetentionDays,
		configuration.dailySummaryRetentionDays,
		configuration.explorationRetentionDays,
		configuration.autoPurgeEnabled,
		configuration.autoCleanupEnabled,
		configuration.dataRetentionYears,
	)

	fun checksum(
		status: RetentionPolicyApprovalStatus,
		configurationGeneration: Long,
		revision: Long,
		opaquePolicyId: String,
		configurationChecksum: String,
	): String = digest(
		"retention-policy-approval-v2",
		status.name,
		configurationGeneration,
		revision,
		opaquePolicyId,
		configurationChecksum,
	)

	private fun digest(vararg values: Any?): String {
		val canonical = values.joinToString(separator = "") { value ->
			val text = value?.toString()
			if (text == null) "-1:" else "${text.length}:$text"
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}
}
