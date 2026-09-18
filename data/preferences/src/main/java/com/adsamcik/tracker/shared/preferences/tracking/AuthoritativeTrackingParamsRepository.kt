package com.adsamcik.tracker.shared.preferences.tracking

import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityScope
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityState
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityUnavailableReason
import com.adsamcik.tracker.shared.preferences.retention.UnavailableRetentionAuthorityProducer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Compatibility projection that makes Room SourcePolicy authoritative for every existing runtime
 * consumer while retaining non-source settings in the legacy Proto DataStore.
 *
 * Source mutations commit policy first. The DataStore write is a downstream compatibility mirror;
 * a failed mirror can be retried, but it cannot weaken or roll back the effective Room policy.
 * Post-commit retention/provider debt is published through
 * [SourcePolicyRevisionReconciliationCoordinator] and never misreported as a failed mutation.
 */
class AuthoritativeTrackingParamsRepository(
	private val legacy: TrackingParamsRepository,
	private val sourcePolicyRepository: SourcePolicyRepository,
	private val applicationScope: CoroutineScope,
	private val trackingStartupGate: TrackingStartupGate,
	private val retentionAuthorityProducer: RetentionAuthorityProducer =
		UnavailableRetentionAuthorityProducer,
	private val ambientStepsPolicyRevisionReconciler: AmbientStepsPolicyRevisionReconciler,
) : TrackingParamsRepository,
	SourcePolicyRevisionReconciliationCoordinator,
	SourcePolicyAuthorityBootstrapCoordinator {
	private val mutationMutex = Mutex()
	private val reconciliationMutex = Mutex()
	private val scheduledMirrorRepairs = ConcurrentHashMap.newKeySet<Long>()
	private val mutableReconciliationState =
		MutableStateFlow<SourcePolicyRevisionReconciliationState>(
			SourcePolicyRevisionReconciliationState.Uninitialized,
		)
	override val reconciliationState: StateFlow<SourcePolicyRevisionReconciliationState> =
		mutableReconciliationState.asStateFlow()

	@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
	private val policyData: Flow<TrackingParamsState> = combine(
		legacy.data,
		sourcePolicyRepository.states,
		::Pair,
	).transformLatest { (legacyState, authority) ->
		if (
			authority == SourcePolicyAuthorityState.Uninitialized &&
			legacyState.legacySettingsMigrationCompleted
		) {
			var failurePublished = false
			while (true) {
				try {
					val snapshot = sourcePolicyRepository.bootstrapFromLegacy(legacyState)
					when (reconcilePolicyRevision(snapshot)) {
						is SourcePolicyRevisionReconciliationResult.Complete -> {
							emit(legacyState.withPolicy(snapshot))
							return@transformLatest
						}
						is SourcePolicyRevisionReconciliationResult.Retryable,
						is SourcePolicyRevisionReconciliationResult.Unverifiable,
						-> {
							if (!failurePublished) {
								emit(legacyState.withSourcesFailClosed())
								failurePublished = true
							}
							delay(BOOTSTRAP_RETRY_DELAY_MS)
						}
					}
				} catch (_: Exception) {
					currentCoroutineContext().ensureActive()
					if (!failurePublished) {
						emit(legacyState.withSourcesFailClosed())
						failurePublished = true
					}
					delay(BOOTSTRAP_RETRY_DELAY_MS)
				}
			}
		}
		when (authority) {
			is SourcePolicyAuthorityState.Active -> {
				var failurePublished = false
				while (true) {
					when (reconcilePolicyRevision(authority.snapshot)) {
						is SourcePolicyRevisionReconciliationResult.Complete -> {
							val projection = legacyState.withPolicy(authority.snapshot)
							emit(projection)
							scheduleLegacyMirrorRepair(legacyState, authority.snapshot)
							return@transformLatest
						}
						is SourcePolicyRevisionReconciliationResult.Retryable,
						is SourcePolicyRevisionReconciliationResult.Unverifiable,
						-> {
							if (!failurePublished) {
								emit(legacyState.withSourcesFailClosed())
								failurePublished = true
							}
							delay(AUTHORITY_RETRY_DELAY_MS)
						}
					}
				}
			}
			is SourcePolicyAuthorityState.Invalid,
			SourcePolicyAuthorityState.Uninitialized,
			-> emit(legacyState.withSourcesFailClosed())
		}
	}.retryWhen { _, _ ->
		currentCoroutineContext().ensureActive()
		emit(TrackingParamsState().withSourcesFailClosed())
		delay(AUTHORITY_RETRY_DELAY_MS)
		true
	}

	private val authoritativeData: Flow<TrackingParamsState> = flow {
		if (trackingStartupGate.reconcile() !is TrackingStartupResult.Ready) {
			emit(TrackingParamsState().withSourcesFailClosed())
			// The Application owns retries. This is a signalled wait, including for a permanent block,
			// so policy observation never becomes an independent recovery poller.
			trackingStartupGate.awaitReady()
		}
		emitAll(policyData)
	}

	/** Keeps bootstrap retry alive after a one-shot service consumer accepts fail-closed state. */
	override val data: Flow<TrackingParamsState> = authoritativeData.shareIn(
		scope = applicationScope,
		started = SharingStarted.Eagerly,
		replay = 1,
	)

	override suspend fun update(block: TrackingParamsState.() -> TrackingParamsState) {
		check(trackingStartupGate.reconcile() is TrackingStartupResult.Ready) {
			"Source settings cannot change before tracking startup recovery is ready"
		}
		data.first() // Complete or await the fail-closed bootstrap before entering the mutation lane.
		val mutation = mutationMutex.withLock {
			val authority = sourcePolicyRepository.currentState()
			check(authority is SourcePolicyAuthorityState.Active) {
				"Source settings cannot change while SourcePolicy is unavailable"
			}
			val current = legacy.data.first().withPolicy(authority.snapshot)
			val requested = current.block()
				.normalizeSourceMutationFrom(current)
				.copy(legacySettingsMigrationCompleted = true)
			val effective = sourcePolicyRepository.replaceCaptureSettings(
				expectedPolicyRevision = authority.snapshot.revision,
				settings = requested,
				reason = REASON_USER_SETTINGS,
			)
			val reconciliation = reconcilePolicyRevision(effective)
			val authoritativeProjection = requested.withPolicy(effective)
			legacy.update { authoritativeProjection }
			SourcePolicyMutation(effective.revision, reconciliation)
		}
		if (mutation.reconciliation is SourcePolicyRevisionReconciliationResult.Complete) {
			data.first { state -> state.sourcePolicyRevision == mutation.revision }
		}
	}

	private fun scheduleLegacyMirrorRepair(
		legacyState: TrackingParamsState,
		snapshot: SourcePolicySnapshot,
	) {
		val projection = legacyState.withPolicy(snapshot)
		if (legacyState.matchesSourceProjection(projection)) return
		if (!scheduledMirrorRepairs.add(snapshot.revision)) return
		applicationScope.launch {
			try {
				while (true) {
					try {
						mutationMutex.withLock {
							val current = sourcePolicyRepository.currentState()
							if (current !is SourcePolicyAuthorityState.Active ||
								current.snapshot.revision != snapshot.revision
							) {
								return@launch
							}
							legacy.update { withPolicy(snapshot) }
						}
						return@launch
					} catch (_: Exception) {
						currentCoroutineContext().ensureActive()
						delay(MIRROR_RETRY_DELAY_MS)
				}
				}
			} finally {
				scheduledMirrorRepairs.remove(snapshot.revision)
			}
		}
	}

	override suspend fun setLocationEnabled(enabled: Boolean) = update {
		withSourceEnabled(TrackingSourceComponent.LOCATION, enabled)
	}

	override suspend fun setActivityEnabled(enabled: Boolean) = update {
		withSourceEnabled(TrackingSourceComponent.ACTIVITY, enabled)
	}

	override suspend fun setStepsEnabled(enabled: Boolean) = update {
		withSourceEnabled(TrackingSourceComponent.STEPS, enabled)
	}

	override suspend fun setWifiEnabled(enabled: Boolean) = update {
		withSourceEnabled(TrackingSourceComponent.WIFI, enabled)
	}

	override suspend fun setCellEnabled(enabled: Boolean) = update {
		withSourceEnabled(TrackingSourceComponent.CELL, enabled)
	}

	override suspend fun setBarometerEnabled(enabled: Boolean) = update {
		withSourceEnabled(TrackingSourceComponent.PRESSURE, enabled)
	}

	override suspend fun setAmbientLocationEnabled(enabled: Boolean) =
		setAmbientEnabled(TrackingSourceComponent.LOCATION, enabled)

	override suspend fun setAmbientStepsEnabled(enabled: Boolean) =
		setAmbientEnabled(TrackingSourceComponent.STEPS, enabled)

	override suspend fun setAmbientWifiEnabled(enabled: Boolean) =
		setAmbientEnabled(TrackingSourceComponent.WIFI, enabled)

	override suspend fun setAmbientCellEnabled(enabled: Boolean) =
		setAmbientEnabled(TrackingSourceComponent.CELL, enabled)

	override suspend fun setTransitionDetectionEnabled(enabled: Boolean) = update {
		copy(transitionDetectionEnabled = enabled)
	}

	override suspend fun setNotificationStyled(enabled: Boolean) = update {
		copy(notificationStyled = enabled)
	}

	override suspend fun setMinDistanceMeters(meters: Int) = update {
		copy(minDistanceMeters = meters.coerceAtLeast(1))
	}

	override suspend fun setMinTimeSeconds(seconds: Int) = update {
		copy(minTimeSeconds = seconds.coerceAtLeast(1))
	}

	override suspend fun setRequiredAccuracyMeters(meters: Int) = update {
		copy(requiredAccuracyMeters = meters.coerceAtLeast(1))
	}

	override suspend fun setPreset(preset: TrackingPreset) = update {
		copy(presetName = preset.name)
	}

	override suspend fun setSourceFrequency(
		component: TrackingSourceComponent,
		frequency: SourceCollectionFrequency,
	) = update {
		withSourceFrequency(component, frequency)
	}

	override suspend fun setAdvancedSourceControlsEnabled(enabled: Boolean) = update {
		copy(advancedSourceControlsEnabled = enabled)
	}

	private suspend fun setAmbientEnabled(
		source: TrackingSourceComponent,
		enabled: Boolean,
	) {
		check(source in APPROVED_AMBIENT_SOURCES) {
			"$source is not an approved ambient product"
		}
		check(trackingStartupGate.reconcile() is TrackingStartupResult.Ready) {
			"Ambient source settings cannot change before tracking startup recovery is ready"
		}
		data.first()
		val mutation = mutationMutex.withLock {
			val authority = sourcePolicyRepository.currentState()
			check(authority is SourcePolicyAuthorityState.Active) {
				"Ambient source settings cannot change while SourcePolicy is unavailable"
			}
			val effective = sourcePolicyRepository.setNonCaptureConsent(
				expectedPolicyRevision = authority.snapshot.revision,
				source = source,
				purpose = SourcePurpose.AMBIENT_PRODUCT,
				eligible = enabled,
				persistenceEligible = enabled,
				reason = REASON_USER_AMBIENT_SETTINGS,
			)
			val reconciliation = reconcilePolicyRevision(effective)
			legacy.update { withPolicy(effective) }
			SourcePolicyMutation(effective.revision, reconciliation)
		}
		if (mutation.reconciliation is SourcePolicyRevisionReconciliationResult.Complete) {
			data.first { state -> state.sourcePolicyRevision == mutation.revision }
		}
	}

	override suspend fun reconcileCurrentPolicyRevision():
		SourcePolicyRevisionReconciliationResult {
		val authority = reconcileAuthorityForRetentionBootstrap()
		val snapshot = when (authority) {
			is SourcePolicyRevisionReconciliationResult.Complete -> authority.snapshot
			is SourcePolicyRevisionReconciliationResult.Retryable -> return authority
			is SourcePolicyRevisionReconciliationResult.Unverifiable -> return authority
		}
		return reconcilePolicyRevision(snapshot)
	}

	override suspend fun reconcileAuthorityForRetentionBootstrap():
		SourcePolicyRevisionReconciliationResult {
		val authority = sourcePolicyRepository.currentState()
		val snapshot = when (authority) {
			is SourcePolicyAuthorityState.Active -> authority.snapshot
			SourcePolicyAuthorityState.Uninitialized -> try {
				sourcePolicyRepository.bootstrapFromLegacy(
					legacy.verifiedSnapshotForPolicyBootstrap(),
				)
			} catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				return SourcePolicyRevisionReconciliationResult.Retryable(
					SourcePolicyRevisionReconciliationDebt(
						policyRevision = null,
						failures = listOf(
							SourcePolicyRevisionReconciliationFailure.SourcePolicyUnavailable,
						),
					),
				).also(::publishReconciliationResult)
			}
			is SourcePolicyAuthorityState.Invalid ->
				return SourcePolicyRevisionReconciliationResult.Unverifiable(
					SourcePolicyRevisionReconciliationDebt(
						policyRevision = null,
						failures = listOf(
							SourcePolicyRevisionReconciliationFailure.SourcePolicyInvalid(
								authority.reason,
							),
						),
					),
				).also(::publishReconciliationResult)
		}
		return SourcePolicyRevisionReconciliationResult.Complete(snapshot)
	}

	private suspend fun reconcilePolicyRevision(
		effective: SourcePolicySnapshot,
	): SourcePolicyRevisionReconciliationResult = reconciliationMutex.withLock {
		val published = mutableReconciliationState.value
		if (
			published is SourcePolicyRevisionReconciliationState.Complete &&
			published.policyRevision == effective.revision
		) {
			return@withLock SourcePolicyRevisionReconciliationResult.Complete(effective)
		}
		mutableReconciliationState.value =
			SourcePolicyRevisionReconciliationState.Reconciling(effective.revision)
		val retentionResults = try {
			retentionAuthorityProducer.reconcileCurrentSettings()
		} catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			listOf(
				RetentionAuthorityResult.Unavailable(
					source = TrackingSourceComponent.STEPS,
					scope = RetentionAuthorityScope.LIVE_AMBIENT,
					reason = RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE,
				),
			)
		}
		val stepsResults = retentionResults.filter { result ->
			result.source == TrackingSourceComponent.STEPS &&
				result.scope == RetentionAuthorityScope.LIVE_AMBIENT
		}
		val stepsPolicy = effective[TrackingSourceComponent.STEPS]
		val expectedState = if (
			stepsPolicy.ambientConsentEpoch != null &&
			stepsPolicy.ambientPersistenceEligible
		) {
			RetentionAuthorityState.ACTIVE
		} else {
			RetentionAuthorityState.REVOKED
		}
		val retentionFailure = when {
			stepsResults.size != 1 ->
				SourcePolicyRevisionReconciliationFailure.RetentionResultSetInvalid
			stepsResults.single() is RetentionAuthorityResult.Unavailable ->
				SourcePolicyRevisionReconciliationFailure.RetentionAuthority(
					stepsResults.single() as RetentionAuthorityResult.Unavailable,
				)
			stepsResults.single().stateOrNull() != expectedState ->
				SourcePolicyRevisionReconciliationFailure.RetentionStateMismatch(
					result = stepsResults.single(),
					expectedState = expectedState,
				)
			else -> null
		}
		val ambientResult = try {
			if (retentionFailure == null) {
				ambientStepsPolicyRevisionReconciler.reconcileAfterRetentionReissue()
			} else {
				ambientStepsPolicyRevisionReconciler.retireAfterRetentionDebt()
			}
		} catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			AmbientStepsPolicyRevisionReconciliation.Retryable(
				"AMBIENT_STEPS_RECONCILIATION_UNAVAILABLE",
			)
		}
		val failures = buildList {
			if (retentionFailure != null) add(retentionFailure)
			if (ambientResult !is AmbientStepsPolicyRevisionReconciliation.Complete) {
				add(
					SourcePolicyRevisionReconciliationFailure.AmbientStepsLifecycle(
						ambientResult,
					),
				)
			}
		}
		if (failures.isNotEmpty()) {
			val debt = SourcePolicyRevisionReconciliationDebt(
				policyRevision = effective.revision,
				failures = failures,
			)
			val result = if (debt.retryable) {
				SourcePolicyRevisionReconciliationResult.Retryable(debt)
			} else {
				SourcePolicyRevisionReconciliationResult.Unverifiable(debt)
			}
			publishReconciliationResult(result)
			return@withLock result
		}
		SourcePolicyRevisionReconciliationResult.Complete(effective).also(::publishReconciliationResult)
	}

	private fun publishReconciliationResult(result: SourcePolicyRevisionReconciliationResult) {
		mutableReconciliationState.value = when (result) {
			is SourcePolicyRevisionReconciliationResult.Complete ->
				SourcePolicyRevisionReconciliationState.Complete(result.snapshot.revision)
			is SourcePolicyRevisionReconciliationResult.Retryable ->
				SourcePolicyRevisionReconciliationState.Debt(result.debt)
			is SourcePolicyRevisionReconciliationResult.Unverifiable ->
				SourcePolicyRevisionReconciliationState.Debt(result.debt)
		}
	}

	private companion object {
		const val BOOTSTRAP_RETRY_DELAY_MS = 250L
		const val AUTHORITY_RETRY_DELAY_MS = 250L
		const val MIRROR_RETRY_DELAY_MS = 250L
		const val REASON_USER_SETTINGS = "USER_SOURCE_SETTINGS"
		const val REASON_USER_AMBIENT_SETTINGS = "USER_AMBIENT_PRODUCT_SETTINGS"
		val APPROVED_AMBIENT_SOURCES = setOf(
			TrackingSourceComponent.LOCATION,
			TrackingSourceComponent.STEPS,
			TrackingSourceComponent.WIFI,
			TrackingSourceComponent.CELL,
		)
	}

	private data class SourcePolicyMutation(
		val revision: Long,
		val reconciliation: SourcePolicyRevisionReconciliationResult,
	)
}

interface AmbientStepsPolicyRevisionReconciler {
	suspend fun reconcileAfterRetentionReissue(): AmbientStepsPolicyRevisionReconciliation
	suspend fun retireAfterRetentionDebt(): AmbientStepsPolicyRevisionReconciliation
}

interface SourcePolicyRevisionReconciliationCoordinator {
	val reconciliationState: StateFlow<SourcePolicyRevisionReconciliationState>

	suspend fun reconcileCurrentPolicyRevision(): SourcePolicyRevisionReconciliationResult
}

/**
 * Provider-free SourcePolicy bootstrap used while a durable deletion marker still closes runtime
 * admission. Completion grants no provider, demand, authorization, or Ambient lifecycle authority.
 */
fun interface SourcePolicyAuthorityBootstrapCoordinator {
	suspend fun reconcileAuthorityForRetentionBootstrap():
		SourcePolicyRevisionReconciliationResult
}

object UnavailableSourcePolicyRevisionReconciliationCoordinator :
	SourcePolicyRevisionReconciliationCoordinator {
	override val reconciliationState: StateFlow<SourcePolicyRevisionReconciliationState> =
		MutableStateFlow(SourcePolicyRevisionReconciliationState.Uninitialized)

	override suspend fun reconcileCurrentPolicyRevision():
		SourcePolicyRevisionReconciliationResult =
		SourcePolicyRevisionReconciliationResult.Retryable(
			SourcePolicyRevisionReconciliationDebt(
				policyRevision = null,
				failures = listOf(
					SourcePolicyRevisionReconciliationFailure.SourcePolicyUnavailable,
				),
			),
		)
}

sealed interface SourcePolicyRevisionReconciliationState {
	data object Uninitialized : SourcePolicyRevisionReconciliationState

	data class Reconciling(val policyRevision: Long) :
		SourcePolicyRevisionReconciliationState {
		init {
			require(policyRevision > 0L)
		}
	}

	data class Complete(val policyRevision: Long) :
		SourcePolicyRevisionReconciliationState {
		init {
			require(policyRevision > 0L)
		}
	}

	data class Debt(val debt: SourcePolicyRevisionReconciliationDebt) :
		SourcePolicyRevisionReconciliationState
}

sealed interface SourcePolicyRevisionReconciliationResult {
	data class Complete(val snapshot: SourcePolicySnapshot) :
		SourcePolicyRevisionReconciliationResult

	data class Retryable(val debt: SourcePolicyRevisionReconciliationDebt) :
		SourcePolicyRevisionReconciliationResult {
		init {
			require(debt.retryable)
		}
	}

	data class Unverifiable(val debt: SourcePolicyRevisionReconciliationDebt) :
		SourcePolicyRevisionReconciliationResult {
		init {
			require(!debt.retryable)
		}
	}
}

data class SourcePolicyRevisionReconciliationDebt(
	val policyRevision: Long?,
	val failures: List<SourcePolicyRevisionReconciliationFailure>,
) {
	init {
		require(policyRevision == null || policyRevision > 0L)
		require(failures.isNotEmpty())
	}

	val retryable: Boolean
		get() = failures.all { it.retryable }
}

sealed interface AmbientStepsPolicyRevisionReconciliation {
	data object Complete : AmbientStepsPolicyRevisionReconciliation

	data class Retryable(val failureCode: String) :
		AmbientStepsPolicyRevisionReconciliation {
		init {
			require(failureCode.isNotBlank())
		}
	}

	data class Unverifiable(val failureCode: String) :
		AmbientStepsPolicyRevisionReconciliation {
		init {
			require(failureCode.isNotBlank())
		}
	}
}

sealed interface SourcePolicyRevisionReconciliationFailure {
	data object SourcePolicyUnavailable : SourcePolicyRevisionReconciliationFailure

	data class SourcePolicyInvalid(val reason: String) :
		SourcePolicyRevisionReconciliationFailure {
		init {
			require(reason.isNotBlank())
		}
	}

	data class RetentionAuthority(
		val failure: RetentionAuthorityResult.Unavailable,
	) : SourcePolicyRevisionReconciliationFailure

	data object RetentionResultSetInvalid : SourcePolicyRevisionReconciliationFailure

	data class RetentionStateMismatch(
		val result: RetentionAuthorityResult,
		val expectedState: RetentionAuthorityState,
	) : SourcePolicyRevisionReconciliationFailure

	data class AmbientStepsLifecycle(
		val result: AmbientStepsPolicyRevisionReconciliation,
	) : SourcePolicyRevisionReconciliationFailure {
		init {
			require(result !is AmbientStepsPolicyRevisionReconciliation.Complete)
		}
	}
}

private val SourcePolicyRevisionReconciliationFailure.retryable: Boolean
	get() = when (this) {
		SourcePolicyRevisionReconciliationFailure.SourcePolicyUnavailable -> true
		is SourcePolicyRevisionReconciliationFailure.SourcePolicyInvalid -> false
		is SourcePolicyRevisionReconciliationFailure.RetentionAuthority ->
			failure.reason !in NON_RETRYABLE_RETENTION_FAILURES
		SourcePolicyRevisionReconciliationFailure.RetentionResultSetInvalid -> false
		is SourcePolicyRevisionReconciliationFailure.RetentionStateMismatch -> false
		is SourcePolicyRevisionReconciliationFailure.AmbientStepsLifecycle ->
			result is AmbientStepsPolicyRevisionReconciliation.Retryable
	}

class SourcePolicyRevisionReconciliationException(
	val failures: List<SourcePolicyRevisionReconciliationFailure>,
) : IllegalStateException("Source policy revision reconciliation remains incomplete") {
	init {
		require(failures.isNotEmpty())
	}
}

private fun RetentionAuthorityResult.stateOrNull(): RetentionAuthorityState? = when (this) {
	is RetentionAuthorityResult.Applied -> state
	is RetentionAuthorityResult.Unchanged -> state
	is RetentionAuthorityResult.Unavailable -> null
}

private val NON_RETRYABLE_RETENTION_FAILURES = setOf(
	RetentionAuthorityUnavailableReason.APPROVAL_REVISION_EXHAUSTED,
	RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH,
	RetentionAuthorityUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
)

private fun TrackingParamsState.matchesSourceProjection(other: TrackingParamsState): Boolean =
	locationEnabled == other.locationEnabled &&
		activityEnabled == other.activityEnabled &&
		stepsEnabled == other.stepsEnabled &&
		wifiEnabled == other.wifiEnabled &&
		cellEnabled == other.cellEnabled &&
		barometerEnabled == other.barometerEnabled &&
		ambientLocationEnabled == other.ambientLocationEnabled &&
		ambientStepsEnabled == other.ambientStepsEnabled &&
		ambientWifiEnabled == other.ambientWifiEnabled &&
		ambientCellEnabled == other.ambientCellEnabled &&
		minTimeSeconds == other.minTimeSeconds &&
		minDistanceMeters == other.minDistanceMeters &&
		requiredAccuracyMeters == other.requiredAccuracyMeters &&
		sourceCollectionSettings == other.sourceCollectionSettings

private fun TrackingParamsState.withPolicy(snapshot: SourcePolicySnapshot): TrackingParamsState {
	val location = snapshot[TrackingSourceComponent.LOCATION]
	val activity = snapshot[TrackingSourceComponent.ACTIVITY]
	val steps = snapshot[TrackingSourceComponent.STEPS]
	val pressure = snapshot[TrackingSourceComponent.PRESSURE]
	val wifi = snapshot[TrackingSourceComponent.WIFI]
	val cell = snapshot[TrackingSourceComponent.CELL]
	return copy(
		locationEnabled = location.enabled,
		activityEnabled = activity.enabled,
		stepsEnabled = steps.enabled,
		barometerEnabled = pressure.enabled,
		wifiEnabled = wifi.enabled,
		cellEnabled = cell.enabled,
		ambientLocationEnabled = location.ambientConsentEpoch != null &&
			location.ambientPersistenceEligible,
		ambientStepsEnabled = steps.ambientConsentEpoch != null &&
			steps.ambientPersistenceEligible,
		ambientWifiEnabled = wifi.ambientConsentEpoch != null &&
			wifi.ambientPersistenceEligible,
		ambientCellEnabled = cell.ambientConsentEpoch != null &&
			cell.ambientPersistenceEligible,
		minTimeSeconds = requireNotNull(location.locationMinTimeSeconds),
		minDistanceMeters = requireNotNull(location.locationMinDistanceMeters),
		requiredAccuracyMeters = requireNotNull(location.locationRequiredAccuracyMeters),
		sourceCollectionSettings = SourceCollectionSettings(
			location = location.qos.toFrequency(),
			activity = activity.qos.toFrequency(),
			steps = steps.qos.toFrequency(),
			pressure = pressure.qos.toFrequency(),
			wifi = wifi.qos.toFrequency(),
			cell = cell.qos.toFrequency(),
		),
		sourceSettingsVersion = TrackingParamsState.CURRENT_SOURCE_SETTINGS_VERSION,
		legacySettingsMigrationCompleted = true,
		sourcePolicyRevision = snapshot.revision,
	)
}

private fun TrackingParamsState.withSourcesFailClosed(): TrackingParamsState = copy(
	locationEnabled = false,
	activityEnabled = false,
	stepsEnabled = false,
	barometerEnabled = false,
	wifiEnabled = false,
	cellEnabled = false,
	ambientLocationEnabled = false,
	ambientStepsEnabled = false,
	ambientWifiEnabled = false,
	ambientCellEnabled = false,
	sourcePolicyRevision = null,
	sourceCollectionSettings = SourceCollectionSettings(
		location = SourceCollectionFrequency.OFF,
		activity = SourceCollectionFrequency.OFF,
		steps = SourceCollectionFrequency.OFF,
		pressure = SourceCollectionFrequency.OFF,
		wifi = SourceCollectionFrequency.OFF,
		cell = SourceCollectionFrequency.OFF,
	),
)

private fun TrackingParamsState.withSourceEnabled(
	source: TrackingSourceComponent,
	enabled: Boolean,
): TrackingParamsState {
	val nextFrequency = frequency(source).forPolicyEnabled(enabled)
	return withSourceFrequency(source, nextFrequency)
}

private fun TrackingParamsState.withSourceFrequency(
	source: TrackingSourceComponent,
	frequency: SourceCollectionFrequency,
): TrackingParamsState {
	val enabled = frequency != SourceCollectionFrequency.OFF
	val nextCollection = when (source) {
		TrackingSourceComponent.LOCATION -> sourceCollectionSettings.copy(location = frequency)
		TrackingSourceComponent.ACTIVITY -> sourceCollectionSettings.copy(activity = frequency)
		TrackingSourceComponent.STEPS -> sourceCollectionSettings.copy(steps = frequency)
		TrackingSourceComponent.PRESSURE -> sourceCollectionSettings.copy(pressure = frequency)
		TrackingSourceComponent.WIFI -> sourceCollectionSettings.copy(wifi = frequency)
		TrackingSourceComponent.CELL -> sourceCollectionSettings.copy(cell = frequency)
	}
	return copy(
		locationEnabled = if (source == TrackingSourceComponent.LOCATION) enabled else locationEnabled,
		activityEnabled = if (source == TrackingSourceComponent.ACTIVITY) enabled else activityEnabled,
		stepsEnabled = if (source == TrackingSourceComponent.STEPS) enabled else stepsEnabled,
		barometerEnabled = if (source == TrackingSourceComponent.PRESSURE) enabled else barometerEnabled,
		wifiEnabled = if (source == TrackingSourceComponent.WIFI) enabled else wifiEnabled,
		cellEnabled = if (source == TrackingSourceComponent.CELL) enabled else cellEnabled,
		sourceCollectionSettings = nextCollection,
	)
}

/**
 * Preserves the existing bulk-update contract while removing Boolean/frequency ambiguity.
 * A caller that changes one representation explicitly controls the source; conflicting changes
 * to both representations fail closed.
 */
private fun TrackingParamsState.normalizeSourceMutationFrom(
	previous: TrackingParamsState,
): TrackingParamsState {
	var normalized = this
	TrackingSourceComponent.entries.forEach { source ->
		val previousEnabled = previous.enabled(source)
		val previousFrequency = previous.frequency(source)
		val requestedEnabled = enabled(source)
		val requestedFrequency = frequency(source)
		val resolvedFrequency = when {
			requestedEnabled != previousEnabled && requestedFrequency == previousFrequency ->
				requestedFrequency.forPolicyEnabled(requestedEnabled)
			requestedFrequency != previousFrequency && requestedEnabled == previousEnabled ->
				requestedFrequency
			requestedEnabled && requestedFrequency != SourceCollectionFrequency.OFF ->
				requestedFrequency
			else -> SourceCollectionFrequency.OFF
		}
		normalized = normalized.withSourceFrequency(source, resolvedFrequency)
	}
	return normalized
}

private fun TrackingParamsState.enabled(source: TrackingSourceComponent): Boolean = when (source) {
	TrackingSourceComponent.LOCATION -> locationEnabled
	TrackingSourceComponent.ACTIVITY -> activityEnabled
	TrackingSourceComponent.STEPS -> stepsEnabled
	TrackingSourceComponent.PRESSURE -> barometerEnabled
	TrackingSourceComponent.WIFI -> wifiEnabled
	TrackingSourceComponent.CELL -> cellEnabled
}

private fun TrackingParamsState.frequency(source: TrackingSourceComponent): SourceCollectionFrequency =
	when (source) {
		TrackingSourceComponent.LOCATION -> sourceCollectionSettings.location
		TrackingSourceComponent.ACTIVITY -> sourceCollectionSettings.activity
		TrackingSourceComponent.STEPS -> sourceCollectionSettings.steps
		TrackingSourceComponent.PRESSURE -> sourceCollectionSettings.pressure
		TrackingSourceComponent.WIFI -> sourceCollectionSettings.wifi
		TrackingSourceComponent.CELL -> sourceCollectionSettings.cell
	}

private fun SourceCollectionFrequency.forPolicyEnabled(enabled: Boolean): SourceCollectionFrequency = when {
	!enabled -> SourceCollectionFrequency.OFF
	this == SourceCollectionFrequency.OFF -> SourceCollectionFrequency.BALANCED
	else -> this
}

private fun SourceQos.toFrequency(): SourceCollectionFrequency =
	SourceCollectionFrequency.fromStableCode(stableCode)
