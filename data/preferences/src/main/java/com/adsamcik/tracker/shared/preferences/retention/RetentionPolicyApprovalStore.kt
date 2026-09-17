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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class ApprovedRetentionPolicy(
	val revision: Long,
	val opaquePolicyId: String,
	val configurationChecksum: String,
	val integrityChecksum: String,
) {
	init {
		require(revision > 0L)
		require(opaquePolicyId.isNotBlank() && opaquePolicyId.length <= 256)
		require(DIGEST.matches(configurationChecksum))
		require(DIGEST.matches(integrityChecksum))
	}

	internal fun isAuthentic(): Boolean =
		integrityChecksum == RetentionPolicyApprovalIntegrity.checksum(
			revision,
			opaquePolicyId,
			configurationChecksum,
		)

	private companion object {
		val DIGEST = Regex("[0-9a-f]{64}")
	}
}

sealed interface ApprovedRetentionPolicyRead {
	data class Available(val policy: ApprovedRetentionPolicy) : ApprovedRetentionPolicyRead
	data class Unavailable(val reason: ApprovedRetentionPolicyUnavailableReason) :
		ApprovedRetentionPolicyRead
}

enum class ApprovedRetentionPolicyUnavailableReason {
	NOT_APPROVED,
	CONFIGURATION_CHANGED,
	INTEGRITY_MISMATCH,
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

	suspend fun approve(configuration: RetentionConfigState): ApprovedRetentionPolicy =
		withContext(ioDispatcher) {
			val configurationChecksum = RetentionPolicyApprovalIntegrity.configurationChecksum(
				configuration,
			)
			var approved: ApprovedRetentionPolicy? = null
			dataStore.edit { preferences ->
				val current = preferences.toApprovalOrNull()
				?.takeIf(ApprovedRetentionPolicy::isAuthentic)
				?.takeIf { it.configurationChecksum == configurationChecksum }
				if (current != null) {
					approved = current
					return@edit
				}
				val storedRevision = preferences[REVISION_KEY] ?: 0L
				check(storedRevision in 0 until Long.MAX_VALUE) {
					"Retention policy revision is exhausted or invalid"
				}
				val revision = storedRevision + 1L
				val opaquePolicyId = nextOpaquePolicyId()
				require(opaquePolicyId.isNotBlank() && opaquePolicyId.length <= 256)
				val value = ApprovedRetentionPolicy(
					revision = revision,
					opaquePolicyId = opaquePolicyId,
					configurationChecksum = configurationChecksum,
					integrityChecksum = RetentionPolicyApprovalIntegrity.checksum(
						revision,
						opaquePolicyId,
						configurationChecksum,
					),
				)
				preferences[REVISION_KEY] = value.revision
				preferences[OPAQUE_POLICY_ID_KEY] = value.opaquePolicyId
				preferences[CONFIGURATION_CHECKSUM_KEY] = value.configurationChecksum
				preferences[INTEGRITY_CHECKSUM_KEY] = value.integrityChecksum
				approved = value
			}
			checkNotNull(approved)
		}

	suspend fun current(
		configuration: RetentionConfigState,
	): ApprovedRetentionPolicyRead = withContext(ioDispatcher) {
		val preferences = dataStore.data.first()
		val approval = preferences.toApprovalOrNull() ?: return@withContext run {
			val reason = if (preferences.hasAnyApprovalValue()) {
				ApprovedRetentionPolicyUnavailableReason.INTEGRITY_MISMATCH
			} else {
				ApprovedRetentionPolicyUnavailableReason.NOT_APPROVED
			}
			ApprovedRetentionPolicyRead.Unavailable(reason)
		}
		if (!approval.isAuthentic()) {
			return@withContext ApprovedRetentionPolicyRead.Unavailable(
				ApprovedRetentionPolicyUnavailableReason.INTEGRITY_MISMATCH,
			)
		}
		if (
			approval.configurationChecksum !=
			RetentionPolicyApprovalIntegrity.configurationChecksum(configuration)
		) {
			return@withContext ApprovedRetentionPolicyRead.Unavailable(
				ApprovedRetentionPolicyUnavailableReason.CONFIGURATION_CHANGED,
			)
		}
		ApprovedRetentionPolicyRead.Available(approval)
	}

	suspend fun resetForTests() {
		dataStore.edit { it.clear() }
	}

	private companion object {
		val REVISION_KEY = longPreferencesKey("revision")
		val OPAQUE_POLICY_ID_KEY = stringPreferencesKey("opaque_policy_id")
		val CONFIGURATION_CHECKSUM_KEY = stringPreferencesKey("configuration_checksum")
		val INTEGRITY_CHECKSUM_KEY = stringPreferencesKey("integrity_checksum")

		fun Preferences.toApprovalOrNull(): ApprovedRetentionPolicy? {
			val revision = this[REVISION_KEY] ?: return null
			val opaquePolicyId = this[OPAQUE_POLICY_ID_KEY] ?: return null
			val configurationChecksum = this[CONFIGURATION_CHECKSUM_KEY] ?: return null
			val integrityChecksum = this[INTEGRITY_CHECKSUM_KEY] ?: return null
			return runCatching {
				ApprovedRetentionPolicy(
					revision,
					opaquePolicyId,
					configurationChecksum,
					integrityChecksum,
				)
			}.getOrNull()
		}

		fun Preferences.hasAnyApprovalValue(): Boolean =
			this[REVISION_KEY] != null ||
				this[OPAQUE_POLICY_ID_KEY] != null ||
				this[CONFIGURATION_CHECKSUM_KEY] != null ||
				this[INTEGRITY_CHECKSUM_KEY] != null
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
		revision: Long,
		opaquePolicyId: String,
		configurationChecksum: String,
	): String = digest(
		"retention-policy-approval-v1",
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
