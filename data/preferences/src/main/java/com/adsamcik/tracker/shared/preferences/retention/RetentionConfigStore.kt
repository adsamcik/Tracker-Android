package com.adsamcik.tracker.shared.preferences.retention

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.store.LegacyPreferenceStore
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

private object RetentionConfigSerializer : Serializer<RetentionConfigProto> {
    override val defaultValue: RetentionConfigProto = RetentionConfigProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): RetentionConfigProto {
        try {
            return RetentionConfigProto.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read retention config proto", e)
        }
    }

    override suspend fun writeTo(t: RetentionConfigProto, output: OutputStream) {
        t.writeTo(output)
    }
}

private val Context.retentionConfigDataStore: DataStore<RetentionConfigProto> by dataStore(
    fileName = "retention_config.pb",
    serializer = RetentionConfigSerializer,
)

class RetentionConfigStore(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val dataStore = context.retentionConfigDataStore
    private val policyApprovalStore = RetentionPolicyApprovalStore(context, ioDispatcher)
    private val updateMutex = Mutex()

    val config: Flow<RetentionConfigState> = flow {
        val migrated = ensureDataSettingsMigrated()
        emit(migrated.toDomain())
        emitAll(dataStore.data.map { it.toDomain() })
    }

    val approvalStatus: Flow<RetentionPolicyApprovalStatus> = combine(
        config,
        policyApprovalStore.records,
    ) { configuration, record ->
        when (record) {
            StoredRetentionPolicyApproval.Missing ->
                RetentionPolicyApprovalStatus.UNAPPROVED
            StoredRetentionPolicyApproval.Invalid ->
                RetentionPolicyApprovalStatus.INVALID
            is StoredRetentionPolicyApproval.Present ->
                record.record.statusFor(configuration)
        }
    }

    internal suspend fun update(
        block: RetentionConfigState.() -> RetentionConfigState,
    ): RetentionPolicyStage = updateMutex.withLock {
        withContext(ioDispatcher) {
            val current = ensureDataSettingsMigrated().toDomain()
            val proposed = current.block()
            val stage = policyApprovalStore.stage(proposed)
            dataStore.updateData { stored ->
                if (stored.toDomain() != current) {
                    throw ConcurrentRetentionConfigurationMutationException()
                }
                proposed.toProto()
            }
            stage
        }
    }

    /**
     * Resets debug/test preferences without manufacturing an approved retention authority.
     *
     * A changed default configuration is published only with its normal pending approval stage;
     * the producer-owned prepare/approve/reconciliation path remains mandatory before use.
     */
    suspend fun resetToDefaultsForDebug(): RetentionPolicyStage =
        update { RetentionConfigState() }

    suspend fun updateWithApproval(
        block: RetentionConfigState.() -> RetentionConfigState,
        prepare: suspend (RetentionPolicyStage) -> RetentionConfigurationApprovalResult,
        approve: suspend (RetentionPolicyStage) -> RetentionConfigurationApprovalResult,
    ): RetentionConfigApplyResult = updateMutex.withLock {
        withContext(ioDispatcher) {
            val current = ensureDataSettingsMigrated().toDomain()
            val proposed = current.block()
            val stage = policyApprovalStore.stage(proposed)
            if (!stage.approvalRequired) {
                return@withContext RetentionConfigApplyResult(
                    stage,
                    published = false,
                    RetentionConfigurationApprovalResult.AlreadyApproved(stage.policy),
                )
            }
            val prepared = prepare(stage)
            if (prepared !is RetentionConfigurationApprovalResult.Prepared ||
                prepared.policy != stage.policy
            ) {
                val failure = prepared as? RetentionConfigurationApprovalResult.Unavailable
                    ?: RetentionConfigurationApprovalResult.Unavailable(
                        RetentionAuthorityUnavailableReason.STALE_CONFIGURATION_GENERATION,
                    )
                return@withContext RetentionConfigApplyResult(
                    stage,
                    published = false,
                    failure,
                )
            }
            dataStore.updateData { stored ->
                if (stored.toDomain() != current) {
                    throw ConcurrentRetentionConfigurationMutationException()
                }
                proposed.toProto()
            }
            val approved = approve(stage)
            val exactApproval = when (approved) {
                is RetentionConfigurationApprovalResult.Approved ->
                    approved.takeIf {
                        it.policy.hasAuthenticChecksum(RetentionPolicyApprovalStatus.APPROVED) &&
                            it.policy.hasSameStableIdentity(stage.policy)
                    }
                is RetentionConfigurationApprovalResult.AlreadyApproved ->
                    approved.takeIf {
                        it.policy.hasAuthenticChecksum(RetentionPolicyApprovalStatus.APPROVED) &&
                            it.policy.hasSameStableIdentity(stage.policy)
                    }
                is RetentionConfigurationApprovalResult.Prepared -> null
                is RetentionConfigurationApprovalResult.Unavailable -> approved
            } ?: RetentionConfigurationApprovalResult.Unavailable(
                RetentionAuthorityUnavailableReason.STALE_CONFIGURATION_GENERATION,
            )
            RetentionConfigApplyResult(stage, published = true, exactApproval)
        }
    }

    suspend fun currentApprovedPolicy(): ApprovedRetentionPolicyRead =
        withContext(ioDispatcher) {
            val current = ensureDataSettingsMigrated().toDomain()
            policyApprovalStore.current(current)
        }

    /** Serializes configuration and approval reads with publication so workers cannot observe the gap. */
    suspend fun currentExactApprovedConfig(): ExactApprovedRetentionConfigRead =
        updateMutex.withLock {
            try {
                readExactApprovedConfigLocked()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ExactApprovedRetentionConfigRead.Unavailable(
                    ExactApprovedRetentionConfigUnavailableReason.STORAGE_UNAVAILABLE,
                )
            }
        }

    /**
     * Serializes one complete destructive operation with retention publication.
     *
     * The immutable [ApprovedRetentionOperation] is the identity carried through every destructive
     * boundary. A settings publication cannot replace or pend that approval until [operation]
     * returns, so a worker never authorizes a later delete with a stale preflight read.
     */
    suspend fun <T> withExactApprovedOperation(
        operation: suspend (ApprovedRetentionOperation) -> T,
    ): ExactApprovedRetentionOperationResult<T> = updateMutex.withLock {
        when (val authority = try {
            readExactApprovedConfigLocked()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ExactApprovedRetentionConfigRead.Unavailable(
                ExactApprovedRetentionConfigUnavailableReason.STORAGE_UNAVAILABLE,
            )
        }) {
            is ExactApprovedRetentionConfigRead.Approved -> {
                val admitted = ApprovedRetentionOperation(
                    configuration = authority.configuration,
                    policy = authority.policy,
                )
                ExactApprovedRetentionOperationResult.Completed(
                    admission = admitted,
                    value = operation(admitted),
                )
            }
            is ExactApprovedRetentionConfigRead.Pending,
            is ExactApprovedRetentionConfigRead.Invalid,
            is ExactApprovedRetentionConfigRead.Unavailable,
            -> ExactApprovedRetentionOperationResult.Rejected(authority)
        }
    }

    private suspend fun readExactApprovedConfigLocked(): ExactApprovedRetentionConfigRead =
        withContext(ioDispatcher) {
            val current = ensureDataSettingsMigrated().toDomain()
            when (val candidate = policyApprovalStore.candidate(current)) {
                is RetentionPolicyCandidateRead.Available -> when (candidate.status) {
                    RetentionPolicyApprovalStatus.APPROVED ->
                        ExactApprovedRetentionConfigRead.Approved(
                            configuration = current,
                            policy = candidate.policy,
                        )
                    RetentionPolicyApprovalStatus.PENDING ->
                        ExactApprovedRetentionConfigRead.Pending(
                            configuration = current,
                            policy = candidate.policy,
                        )
                    RetentionPolicyApprovalStatus.UNAPPROVED,
                    RetentionPolicyApprovalStatus.INVALID,
                    -> ExactApprovedRetentionConfigRead.Invalid(
                        ExactApprovedRetentionConfigInvalidReason.APPROVAL_STATE_INVALID,
                    )
                }
                is RetentionPolicyCandidateRead.Unavailable -> when (candidate.reason) {
                    ApprovedRetentionPolicyUnavailableReason.NOT_APPROVED ->
                        ExactApprovedRetentionConfigRead.Unavailable(
                            ExactApprovedRetentionConfigUnavailableReason.NOT_APPROVED,
                        )
                    ApprovedRetentionPolicyUnavailableReason.CONFIGURATION_CHANGED ->
                        ExactApprovedRetentionConfigRead.Invalid(
                            ExactApprovedRetentionConfigInvalidReason.CONFIGURATION_MISMATCH,
                        )
                    ApprovedRetentionPolicyUnavailableReason.INTEGRITY_MISMATCH ->
                        ExactApprovedRetentionConfigRead.Invalid(
                            ExactApprovedRetentionConfigInvalidReason.APPROVAL_INTEGRITY_MISMATCH,
                        )
                    ApprovedRetentionPolicyUnavailableReason.PENDING_APPROVAL ->
                        ExactApprovedRetentionConfigRead.Invalid(
                            ExactApprovedRetentionConfigInvalidReason.APPROVAL_STATE_INVALID,
                        )
                }
            }
        }

    suspend fun currentPolicyCandidate(): RetentionPolicyCandidateRead =
        withContext(ioDispatcher) {
            val current = ensureDataSettingsMigrated().toDomain()
            policyApprovalStore.candidate(current)
        }

    suspend fun pendingPolicyCandidate(): RetentionPolicyCandidateRead =
        policyApprovalStore.pending()

    suspend fun markPolicyApproved(expected: ApprovedRetentionPolicy): ApprovedRetentionPolicy? =
        policyApprovalStore.markApproved(expected)

    /**
     * One-time migration of auto_cleanup_enabled and data_retention_years
     * from the legacy SharedPreferences to Proto DataStore.
     */
    private suspend fun ensureDataSettingsMigrated(): RetentionConfigProto =
        withContext(ioDispatcher) {
            var clearLegacyKeys = false
            val legacyPrefs = LegacyPreferenceStore.freshSnapshot(context)
            val hasAutoCleanup = legacyPrefs.hasKey("autoCleanupOldData")
            val hasRetentionYears = legacyPrefs.hasKey("dataRetentionYears")
            val autoCleanup = if (hasAutoCleanup) {
                legacyPrefs[booleanPreferencesKey("autoCleanupOldData")] ?: false
            } else {
                false
            }

            val retentionYears = if (hasRetentionYears) {
                legacyPrefs.intOrString(
                    "dataRetentionYears",
                    RetentionConfigState.DEFAULT_RETENTION_YEARS
                ).coerceAtLeast(0)
            } else {
                RetentionConfigState.DEFAULT_RETENTION_YEARS
            }
            val migrated = dataStore.updateData { current ->
                if (current.dataSettingsLegacyMigrated &&
                    !hasAutoCleanup &&
                    !hasRetentionYears
                ) {
                    return@updateData current
                }

                clearLegacyKeys = hasAutoCleanup || hasRetentionYears
                val builder = current.toBuilder()
                    .setAutoCleanupEnabled(autoCleanup)
                    .setDataRetentionYears(retentionYears)
                    .setDataSettingsLegacyMigrated(true)

                if (current.initialized || hasAutoCleanup || hasRetentionYears) {
                    val currentState = current.toDomain()
                    builder
                        .setRawDataRetentionDays(currentState.rawDataRetentionDays)
                        .setWifiCellRetentionDays(currentState.wifiCellRetentionDays)
                        .setTripRetentionDays(currentState.tripRetentionDays)
                        .setDailySummaryRetentionDays(currentState.dailySummaryRetentionDays)
                        .setExplorationRetentionDays(currentState.explorationRetentionDays)
                        .setAutoPurgeEnabled(currentState.autoPurgeEnabled)
                        .setInitialized(true)
                }

                builder.build()
            }
            if (clearLegacyKeys) {
                policyApprovalStore.stage(migrated.toDomain())
                runCatching {
                    Preferences(context).editSuspend {
                        remove("autoCleanupOldData")
                        remove("dataRetentionYears")
                    }
                }
            }
            migrated
        }
}

class ConcurrentRetentionConfigurationMutationException : IllegalStateException(
    "Retention configuration changed while its pending approval was staged",
)

data class RetentionConfigApplyResult(
    val stage: RetentionPolicyStage,
    val published: Boolean,
    val approval: RetentionConfigurationApprovalResult,
)

sealed interface ExactApprovedRetentionConfigRead {
    data class Approved(
        val configuration: RetentionConfigState,
        val policy: ApprovedRetentionPolicy,
    ) : ExactApprovedRetentionConfigRead

    data class Pending(
        val configuration: RetentionConfigState,
        val policy: ApprovedRetentionPolicy,
    ) : ExactApprovedRetentionConfigRead

    data class Invalid(
        val reason: ExactApprovedRetentionConfigInvalidReason,
    ) : ExactApprovedRetentionConfigRead

    data class Unavailable(
        val reason: ExactApprovedRetentionConfigUnavailableReason,
    ) : ExactApprovedRetentionConfigRead
}

data class ApprovedRetentionOperation(
    val configuration: RetentionConfigState,
    val policy: ApprovedRetentionPolicy,
) {
    fun requireIdentity(expected: ApprovedRetentionPolicy = policy) {
        check(policy.hasSameStableIdentity(expected)) {
            "Destructive retention approval identity changed inside an admitted operation"
        }
    }

    fun retentionFloorOperationId(
		requestedRetainedFromMs: Long,
		requestedAtMs: Long,
		workExecutionId: String? = null,
	): String {
        require(requestedRetainedFromMs >= 0L)
		require(requestedAtMs >= 0L)
		require(workExecutionId == null || workExecutionId.isNotBlank())
        return "retention-floor-v1:${policy.configurationGeneration}:${policy.revision}:" +
            "${policy.opaquePolicyId}:${policy.configurationChecksum}:" +
			"$requestedRetainedFromMs:$requestedAtMs" +
			(workExecutionId?.let { ":work:$it" } ?: "")
    }
}

sealed interface ExactApprovedRetentionOperationResult<out T> {
    data class Completed<T>(
        val admission: ApprovedRetentionOperation,
        val value: T,
    ) : ExactApprovedRetentionOperationResult<T>

    data class Rejected(
        val authority: ExactApprovedRetentionConfigRead,
    ) : ExactApprovedRetentionOperationResult<Nothing> {
        init {
            require(authority !is ExactApprovedRetentionConfigRead.Approved)
        }
    }
}

enum class ExactApprovedRetentionConfigInvalidReason {
    CONFIGURATION_MISMATCH,
    APPROVAL_INTEGRITY_MISMATCH,
    APPROVAL_STATE_INVALID,
}

enum class ExactApprovedRetentionConfigUnavailableReason {
    NOT_APPROVED,
    STORAGE_UNAVAILABLE,
}

suspend fun resetRetentionConfigForTests(context: Context) {
    context.retentionConfigDataStore.updateData {
        RetentionConfigProto.getDefaultInstance()
    }
    RetentionPolicyApprovalStore(context, kotlinx.coroutines.Dispatchers.IO).resetForTests()
}

private fun RetentionConfigProto.toDomain(): RetentionConfigState {
    if (!initialized) return RetentionConfigState()
    return RetentionConfigState(
        rawDataRetentionDays = rawDataRetentionDays.withDefaultIfNegative(RetentionConfigState.DEFAULT_RAW_DAYS),
        wifiCellRetentionDays = wifiCellRetentionDays.withDefaultIfNegative(RetentionConfigState.DEFAULT_RAW_DAYS),
        tripRetentionDays = tripRetentionDays.withDefaultIfNegative(RetentionConfigState.DEFAULT_RAW_DAYS),
        dailySummaryRetentionDays = dailySummaryRetentionDays.withDefaultIfNegative(
            RetentionConfigState.DEFAULT_DAILY_SUMMARY_DAYS
        ),
        explorationRetentionDays = explorationRetentionDays.coerceAtLeast(0),
        autoPurgeEnabled = autoPurgeEnabled,
        autoCleanupEnabled = autoCleanupEnabled,
        dataRetentionYears = dataRetentionYears.withDefaultIfNegative(RetentionConfigState.DEFAULT_RETENTION_YEARS),
    )
}

private fun RetentionConfigState.toProto(): RetentionConfigProto =
    RetentionConfigProto.newBuilder()
        .setRawDataRetentionDays(rawDataRetentionDays)
        .setWifiCellRetentionDays(wifiCellRetentionDays)
        .setTripRetentionDays(tripRetentionDays)
        .setDailySummaryRetentionDays(dailySummaryRetentionDays)
        .setExplorationRetentionDays(explorationRetentionDays)
        .setAutoPurgeEnabled(autoPurgeEnabled)
        .setAutoCleanupEnabled(autoCleanupEnabled)
        .setDataRetentionYears(dataRetentionYears)
        .setInitialized(true)
        .setDataSettingsLegacyMigrated(true)
        .build()

private fun Int.withDefaultIfNegative(default: Int): Int = if (this < 0) default else this

private fun androidx.datastore.preferences.core.Preferences.hasKey(key: String): Boolean =
    asMap().keys.any { it.name == key }

private fun androidx.datastore.preferences.core.Preferences.intOrString(key: String, default: Int): Int {
    return when (val value = asMap().entries.firstOrNull { it.key.name == key }?.value) {
        is Int -> value
        is String -> value.toIntOrNull() ?: default
        else -> default
    }
}
