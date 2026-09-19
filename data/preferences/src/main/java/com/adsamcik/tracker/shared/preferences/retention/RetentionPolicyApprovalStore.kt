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

internal data class StoredRetentionPolicyApprovals(
	val approved: RetentionPolicyApprovalRecord?,
	val pending: RetentionPolicyApprovalRecord?,
	val invalid: Boolean,
)

private val Context.retentionPolicyApprovalDataStore: DataStore<Preferences> by preferencesDataStore(
	name = "retention_policy_approval",
)

internal class RetentionPolicyApprovalStore(
	context: Context,
	private val ioDispatcher: CoroutineDispatcher,
	private val nextOpaquePolicyId: () -> String = { UUID.randomUUID().toString() },
) {
	private val dataStore = context.applicationContext.retentionPolicyApprovalDataStore

	val records: Flow<StoredRetentionPolicyApprovals> = dataStore.data.map { preferences ->
		preferences.toStoredApprovals()
	}

	suspend fun stage(configuration: RetentionConfigState): RetentionPolicyStage =
		withContext(ioDispatcher) {
			val configurationChecksum = RetentionPolicyApprovalIntegrity.configurationChecksum(
				configuration,
			)
			var stage: RetentionPolicyStage? = null
			dataStore.edit { preferences ->
				val records = preferences.toStoredApprovals()
				check(!records.invalid) { "Retention approval storage is invalid" }
				val current = records.approved
					?.takeIf { it.policy.configurationChecksum == configurationChecksum }
				if (current != null) {
					stage = RetentionPolicyStage(
						current.policy,
						approvalRequired = false,
					)
					return@edit
				}
				val pending = records.pending
					?.takeIf { it.policy.configurationChecksum == configurationChecksum }
				if (pending != null) {
					stage = RetentionPolicyStage(pending.policy, approvalRequired = true)
					return@edit
				}
				val storedGeneration = listOfNotNull(
					records.approved?.policy?.configurationGeneration,
					records.pending?.policy?.configurationGeneration,
				).maxOrNull() ?: 0L
				val storedRevision = listOfNotNull(
					records.approved?.policy?.revision,
					records.pending?.policy?.revision,
				).maxOrNull() ?: 0L
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
				preferences.writePending(pending)
				stage = RetentionPolicyStage(pending.policy, approvalRequired = true)
			}
			checkNotNull(stage)
		}

	suspend fun current(
		configuration: RetentionConfigState,
	): ApprovedRetentionPolicyRead = withContext(ioDispatcher) {
		val preferences = dataStore.data.first()
		val records = preferences.toStoredApprovals()
		if (records.invalid) {
			return@withContext ApprovedRetentionPolicyRead.Unavailable(
				ApprovedRetentionPolicyUnavailableReason.INTEGRITY_MISMATCH,
			)
		}
		val checksum = RetentionPolicyApprovalIntegrity.configurationChecksum(configuration)
		records.approved?.takeIf { it.policy.configurationChecksum == checksum }?.let {
			return@withContext ApprovedRetentionPolicyRead.Available(it.policy)
		}
		if (records.pending?.policy?.configurationChecksum == checksum) {
			ApprovedRetentionPolicyRead.Unavailable(
				ApprovedRetentionPolicyUnavailableReason.PENDING_APPROVAL,
			)
		} else {
			ApprovedRetentionPolicyRead.Unavailable(
				if (records.approved == null) {
					ApprovedRetentionPolicyUnavailableReason.NOT_APPROVED
				} else {
					ApprovedRetentionPolicyUnavailableReason.CONFIGURATION_CHANGED
				},
			)
		}
	}

	suspend fun candidate(
		configuration: RetentionConfigState,
	): RetentionPolicyCandidateRead = withContext(ioDispatcher) {
		val preferences = dataStore.data.first()
		val records = preferences.toStoredApprovals()
		if (records.invalid) {
			return@withContext RetentionPolicyCandidateRead.Unavailable(
				ApprovedRetentionPolicyUnavailableReason.INTEGRITY_MISMATCH,
			)
		}
		val checksum = RetentionPolicyApprovalIntegrity.configurationChecksum(configuration)
		records.approved?.takeIf { it.policy.configurationChecksum == checksum }?.let {
			return@withContext RetentionPolicyCandidateRead.Available(
				it.policy,
				RetentionPolicyApprovalStatus.APPROVED,
			)
		}
		records.pending?.takeIf { it.policy.configurationChecksum == checksum }?.let {
			return@withContext RetentionPolicyCandidateRead.Available(
				it.policy,
				RetentionPolicyApprovalStatus.PENDING,
			)
		}
		RetentionPolicyCandidateRead.Unavailable(
			if (records.approved == null && records.pending == null) {
				ApprovedRetentionPolicyUnavailableReason.NOT_APPROVED
			} else {
				ApprovedRetentionPolicyUnavailableReason.CONFIGURATION_CHANGED
			},
		)
	}

	suspend fun pending(): RetentionPolicyCandidateRead = withContext(ioDispatcher) {
		val preferences = dataStore.data.first()
		val records = preferences.toStoredApprovals()
		if (records.invalid) {
			return@withContext RetentionPolicyCandidateRead.Unavailable(
				ApprovedRetentionPolicyUnavailableReason.INTEGRITY_MISMATCH,
			)
		}
		val record = records.pending
		if (record == null) {
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
				val records = preferences.toStoredApprovals()
				val current = records.pending
				if (records.invalid ||
					current == null ||
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
				preferences.writeApproved(value)
				preferences.clearPending()
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
		val PENDING_GENERATION_KEY = longPreferencesKey("pending_configuration_generation")
		val PENDING_REVISION_KEY = longPreferencesKey("pending_revision")
		val PENDING_STATE_KEY = stringPreferencesKey("pending_state")
		val PENDING_OPAQUE_POLICY_ID_KEY = stringPreferencesKey("pending_opaque_policy_id")
		val PENDING_CONFIGURATION_CHECKSUM_KEY =
			stringPreferencesKey("pending_configuration_checksum")
		val PENDING_INTEGRITY_CHECKSUM_KEY = stringPreferencesKey("pending_integrity_checksum")

		fun Preferences.toStoredApprovals(): StoredRetentionPolicyApprovals {
			val primary = toRecordOrNull(
				GENERATION_KEY,
				REVISION_KEY,
				STATE_KEY,
				OPAQUE_POLICY_ID_KEY,
				CONFIGURATION_CHECKSUM_KEY,
				INTEGRITY_CHECKSUM_KEY,
			)
			val separatePending = toRecordOrNull(
				PENDING_GENERATION_KEY,
				PENDING_REVISION_KEY,
				PENDING_STATE_KEY,
				PENDING_OPAQUE_POLICY_ID_KEY,
				PENDING_CONFIGURATION_CHECKSUM_KEY,
				PENDING_INTEGRITY_CHECKSUM_KEY,
			)
			val invalid = (
				primary == null && hasAnyApprovalValue(
					GENERATION_KEY,
					REVISION_KEY,
					STATE_KEY,
					OPAQUE_POLICY_ID_KEY,
					CONFIGURATION_CHECKSUM_KEY,
					INTEGRITY_CHECKSUM_KEY,
				)
			) || (
				separatePending == null && hasAnyApprovalValue(
					PENDING_GENERATION_KEY,
					PENDING_REVISION_KEY,
					PENDING_STATE_KEY,
					PENDING_OPAQUE_POLICY_ID_KEY,
					PENDING_CONFIGURATION_CHECKSUM_KEY,
					PENDING_INTEGRITY_CHECKSUM_KEY,
				)
			) || primary?.isAuthentic() == false ||
				separatePending?.isAuthentic() == false ||
				(primary != null && primary.status !in setOf(
					RetentionPolicyApprovalStatus.APPROVED,
					RetentionPolicyApprovalStatus.PENDING,
				)) ||
				(separatePending != null &&
					separatePending.status != RetentionPolicyApprovalStatus.PENDING)
			return StoredRetentionPolicyApprovals(
				approved = primary?.takeIf {
					it.status == RetentionPolicyApprovalStatus.APPROVED
				},
				pending = separatePending ?: primary?.takeIf {
					it.status == RetentionPolicyApprovalStatus.PENDING
				},
				invalid = invalid,
			)
		}

		fun Preferences.toRecordOrNull(
			generationKey: androidx.datastore.preferences.core.Preferences.Key<Long>,
			revisionKey: androidx.datastore.preferences.core.Preferences.Key<Long>,
			stateKey: androidx.datastore.preferences.core.Preferences.Key<String>,
			opaquePolicyIdKey: androidx.datastore.preferences.core.Preferences.Key<String>,
			configurationChecksumKey: androidx.datastore.preferences.core.Preferences.Key<String>,
			integrityChecksumKey: androidx.datastore.preferences.core.Preferences.Key<String>,
		): RetentionPolicyApprovalRecord? {
			val generation = this[generationKey] ?: return null
			val revision = this[revisionKey] ?: return null
			val state = this[stateKey] ?: return null
			val opaquePolicyId = this[opaquePolicyIdKey] ?: return null
			val configurationChecksum = this[configurationChecksumKey] ?: return null
			val integrityChecksum = this[integrityChecksumKey] ?: return null
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

		fun Preferences.hasAnyApprovalValue(
			vararg keys: androidx.datastore.preferences.core.Preferences.Key<*>,
		): Boolean = keys.any { key -> asMap().containsKey(key) }

		fun androidx.datastore.preferences.core.MutablePreferences.writeApproved(
			value: RetentionPolicyApprovalRecord,
		) {
			this[GENERATION_KEY] = value.policy.configurationGeneration
			this[REVISION_KEY] = value.policy.revision
			this[STATE_KEY] = value.status.name
			this[OPAQUE_POLICY_ID_KEY] = value.policy.opaquePolicyId
			this[CONFIGURATION_CHECKSUM_KEY] = value.policy.configurationChecksum
			this[INTEGRITY_CHECKSUM_KEY] = value.policy.integrityChecksum
		}

		fun androidx.datastore.preferences.core.MutablePreferences.writePending(
			value: RetentionPolicyApprovalRecord,
		) {
			this[PENDING_GENERATION_KEY] = value.policy.configurationGeneration
			this[PENDING_REVISION_KEY] = value.policy.revision
			this[PENDING_STATE_KEY] = value.status.name
			this[PENDING_OPAQUE_POLICY_ID_KEY] = value.policy.opaquePolicyId
			this[PENDING_CONFIGURATION_CHECKSUM_KEY] = value.policy.configurationChecksum
			this[PENDING_INTEGRITY_CHECKSUM_KEY] = value.policy.integrityChecksum
		}

		fun androidx.datastore.preferences.core.MutablePreferences.clearPending() {
			remove(PENDING_GENERATION_KEY)
			remove(PENDING_REVISION_KEY)
			remove(PENDING_STATE_KEY)
			remove(PENDING_OPAQUE_POLICY_ID_KEY)
			remove(PENDING_CONFIGURATION_CHECKSUM_KEY)
			remove(PENDING_INTEGRITY_CHECKSUM_KEY)
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
