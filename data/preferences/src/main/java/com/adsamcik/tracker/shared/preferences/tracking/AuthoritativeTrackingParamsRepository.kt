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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
// Keep source settings and revision recovery in their existing single policy/mutation owner.
@Suppress("TooManyFunctions")
class AuthoritativeTrackingParamsRepository(
	private val legacy: TrackingParamsRepository,
	private val sourcePolicyRepository: SourcePolicyRepository,
	private val applicationScope: CoroutineScope,
	private val trackingStartupGate: TrackingStartupGate,
	private val retentionAuthorityProducer: RetentionAuthorityProducer =
		UnavailableRetentionAuthorityProducer,
	private val ambientStepsPolicyRevisionReconciler: AmbientStepsPolicyRevisionReconciler,
	private val setupCompleted: Flow<Boolean> = flowOf(true),
) : TrackingParamsRepository,
	SourcePolicyRevisionReconciliationCoordinator,
	SourcePolicyAuthorityBootstrapCoordinator {
	private val mutationMutex = Mutex()
	private val reconciliationMutex = Mutex()
	@Volatile private var lastCompletedStartupGeneration = -1L
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
		setupCompleted,
	) { legacyState, authority, isSetupComplete ->
		Triple(legacyState, authority, isSetupComplete)
	}.transformLatest { (legacyState, authority, isSetupComplete) ->
		if (
			authority == SourcePolicyAuthorityState.Uninitialized &&
			legacyState.legacySettingsMigrationCompleted
		) {
			while (true) {
				try {
					val snapshot = sourcePolicyRepository.bootstrapFromLegacy(legacyState)
					val startupGeneration = trackingStartupGate.currentGeneration
					when (val result = reconcilePolicyRevision(snapshot)) {
						is SourcePolicyRevisionReconciliationResult.Complete -> {
							emit(legacyState.withPolicy(snapshot))
							return@transformLatest
						}
						is SourcePolicyRevisionReconciliationResult.Retryable,
						is SourcePolicyRevisionReconciliationResult.Unverifiable,
						-> {
							emit(reconciliationDebtProjection(legacyState, snapshot, result, startupGeneration))
							delay(BOOTSTRAP_RETRY_DELAY_MS)
						}
					}
				} catch (_: Exception) {
					currentCoroutineContext().ensureActive()
					emit(legacyState.withSourcesFailClosed())
					delay(BOOTSTRAP_RETRY_DELAY_MS)
				}
			}
		}
		when (authority) {
			is SourcePolicyAuthorityState.Active -> {
				var effectiveSnapshot = authority.snapshot
				while (true) {
					try {
						effectiveSnapshot = convergeSetupChoices(effectiveSnapshot, legacyState, isSetupComplete)
					} catch (_: Exception) {
						currentCoroutineContext().ensureActive()
						emit(legacyState.withSourcesFailClosed())
						delay(AUTHORITY_RETRY_DELAY_MS)
						continue
					}
					// A mutation may have reconciled this revision before its legacy mirror completes.
					// Publish that exact revision without waiting for the mirror's mutation lock.
					val startupGeneration = trackingStartupGate.currentGeneration
					val alreadyReconciled = isReconciledAtReadyGeneration(effectiveSnapshot)
					val result = if (alreadyReconciled) {
						SourcePolicyRevisionReconciliationResult.Complete(effectiveSnapshot)
					} else {
						mutationMutex.withLock {
							val current = sourcePolicyRepository.currentState()
							if (current is SourcePolicyAuthorityState.Active) {
								effectiveSnapshot = current.snapshot
								reconcilePolicyRevision(effectiveSnapshot)
							} else {
								sourcePolicyUnavailable()
							}
						}
					}
					when (result) {
						is SourcePolicyRevisionReconciliationResult.Complete -> {
							if (alreadyReconciled && !isReconciledAtReadyGeneration(effectiveSnapshot)) {
								continue
							}
							val projection = legacyState.withPolicy(effectiveSnapshot)
							emit(projection)
							scheduleLegacyMirrorRepair(legacyState, effectiveSnapshot)
							return@transformLatest
						}
						is SourcePolicyRevisionReconciliationResult.Retryable,
						is SourcePolicyRevisionReconciliationResult.Unverifiable,
						-> {
							emit(reconciliationDebtProjection(legacyState, effectiveSnapshot, result,
								startupGeneration))
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
	}.distinctUntilChanged()

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
			val normalized = current.block().normalizeSourceMutationFrom(current)
			val requested = normalized.copy(ambientStepsEnabled = normalized.stepsEnabled)
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
			.copy(ambientStepsEnabled = enabled)
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

	override suspend fun setAmbientStepsEnabled(enabled: Boolean) = setStepsEnabled(enabled)

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

	/** Reconciles the current saved policy without replacing the user’s source choices. */
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

	override suspend fun reconcileCurrentPolicyRevisionWithinReadyOperation(
		expectedStartupGeneration: Long,
	): SourcePolicyRevisionReconciliationResult {
		if (!trackingStartupGate.isReadyGeneration(expectedStartupGeneration)) {
			return startupGenerationUnavailable(
				policyRevision = null,
				expectedStartupGeneration = expectedStartupGeneration,
			)
		}
		val authority = reconcileAuthorityForRetentionBootstrap()
		val snapshot = when (authority) {
			is SourcePolicyRevisionReconciliationResult.Complete -> authority.snapshot
			is SourcePolicyRevisionReconciliationResult.Retryable -> return authority
			is SourcePolicyRevisionReconciliationResult.Unverifiable -> return authority
		}
		return reconcilePolicyRevisionAtReadyGeneration(snapshot, expectedStartupGeneration)
	}

	/** Prepares source policy only, without granting provider acquisition while deletion is closed. */
	override suspend fun reconcileAuthorityForRetentionBootstrap():
		SourcePolicyRevisionReconciliationResult {
		val authority = sourcePolicyRepository.currentState()
		val snapshot = when (authority) {
			is SourcePolicyAuthorityState.Active -> try {
				convergeSetupChoices(authority.snapshot, legacy.data.first(), setupCompleted.first())
			} catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				return sourcePolicyUnavailable()
			}
			SourcePolicyAuthorityState.Uninitialized -> try {
				sourcePolicyRepository.bootstrapFromLegacy(
					legacy.verifiedSnapshotForPolicyBootstrap(),
				)
			} catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				return sourcePolicyUnavailable()
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
		val generation = trackingStartupGate.currentGeneration
		if (mutableReconciliationState.value is SourcePolicyRevisionReconciliationState.Complete &&
			!trackingStartupGate.isReadyGeneration(generation)
		) {
			startupGenerationUnavailable(snapshot.revision, generation)
		}
		return SourcePolicyRevisionReconciliationResult.Complete(snapshot)
	}

	private suspend fun convergeSetupChoices(
		observed: SourcePolicySnapshot,
		legacyState: TrackingParamsState,
		isSetupComplete: Boolean,
	): SourcePolicySnapshot {
		val steps = observed[TrackingSourceComponent.STEPS]
		val stepsNeedReconciliation =
			(steps.ambientConsentEpoch != null && steps.ambientPersistenceEligible) != steps.enabled
		val controlIsEligible =
			observed[TrackingSourceComponent.ACTIVITY].controlConsentEpoch != null
		val controlShouldBeEligible = isSetupComplete && legacyState.autoTrackingMode > 0
		val controlNeedsReconciliation = controlIsEligible != controlShouldBeEligible
		if (!controlNeedsReconciliation &&
			(!stepsNeedReconciliation || (steps.enabled && !isSetupComplete))
		) return observed
		return mutationMutex.withLock {
			val current = (sourcePolicyRepository.currentState() as? SourcePolicyAuthorityState.Active)
				?.snapshot ?: return@withLock observed
			if (current.revision != observed.revision) return@withLock current
			sourcePolicyRepository.replaceCaptureSettings(
				expectedPolicyRevision = current.revision,
				settings = legacyState.withPolicy(current).copy(ambientStepsEnabled = steps.enabled),
				reason = REASON_SETUP_CHOICE_RECONCILIATION,
			)
		}
	}

	private fun sourcePolicyUnavailable(): SourcePolicyRevisionReconciliationResult.Retryable =
		SourcePolicyRevisionReconciliationResult.Retryable(
			SourcePolicyRevisionReconciliationDebt(
				policyRevision = null,
				failures = listOf(SourcePolicyRevisionReconciliationFailure.SourcePolicyUnavailable),
			),
		).also(::publishReconciliationResult)

	/** Saved choices remain authoritative when only the independent all-day Steps owner needs repair. */
	private fun reconciliationDebtProjection(
		legacyState: TrackingParamsState,
		snapshot: SourcePolicySnapshot,
		result: SourcePolicyRevisionReconciliationResult,
		startupGeneration: Long,
	): TrackingParamsState {
		val debt = when (result) {
			is SourcePolicyRevisionReconciliationResult.Retryable -> result.debt
			is SourcePolicyRevisionReconciliationResult.Unverifiable -> result.debt
			is SourcePolicyRevisionReconciliationResult.Complete -> null
		}
		return if (debt?.hasOnlyStepsFailures == true && debt.policyRevision == snapshot.revision &&
			trackingStartupGate.isReadyGeneration(startupGeneration)) {
			legacyState.withPolicy(snapshot)
		} else {
			legacyState.withSourcesFailClosed()
		}
	}

	private suspend fun reconcilePolicyRevision(
		effective: SourcePolicySnapshot,
	): SourcePolicyRevisionReconciliationResult {
		val startupGeneration = trackingStartupGate.currentGeneration
		// Authority-only bootstrap may run behind the deletion marker, but retention/provider
		// lifecycle work is admitted only by the exact current Ready generation.
		return trackingStartupGate.withReadyGenerationOperation(startupGeneration) {
			reconcilePolicyRevisionAtReadyGeneration(effective, startupGeneration)
		} ?: startupGenerationUnavailable(effective.revision, startupGeneration)
	}

	private fun isReconciledAtReadyGeneration(snapshot: SourcePolicySnapshot): Boolean {
		val generation = trackingStartupGate.currentGeneration
		val state = mutableReconciliationState.value
		return state is SourcePolicyRevisionReconciliationState.Complete &&
			state.policyRevision == snapshot.revision &&
			lastCompletedStartupGeneration == generation &&
			trackingStartupGate.isReadyGeneration(generation)
	}

	private suspend fun reconcilePolicyRevisionAtReadyGeneration(
		effective: SourcePolicySnapshot,
		startupGeneration: Long,
	): SourcePolicyRevisionReconciliationResult = reconciliationMutex.withLock {
		if (!trackingStartupGate.isReadyGeneration(startupGeneration)) {
			return@withLock startupGenerationUnavailable(
				effective.revision,
				startupGeneration,
			)
		}
		val published = mutableReconciliationState.value
		if (
			published is SourcePolicyRevisionReconciliationState.Complete &&
			published.policyRevision == effective.revision &&
			lastCompletedStartupGeneration == startupGeneration
		) {
			return@withLock SourcePolicyRevisionReconciliationResult.Complete(effective)
		}
		mutableReconciliationState.value =
			SourcePolicyRevisionReconciliationState.Reconciling(effective.revision)
		val retentionFailure = stepsRetentionFailure(effective, reconcileRetention())
		if (!trackingStartupGate.isReadyGeneration(startupGeneration)) {
			return@withLock startupGenerationUnavailable(
				effective.revision,
				startupGeneration,
			)
		}
		val ambientResult = reconcileAmbientSteps(retentionFailure)
		completePolicyReconciliation(effective, startupGeneration, retentionFailure, ambientResult)
	}

	private suspend fun reconcileRetention(): List<RetentionAuthorityResult> = try {
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

	private fun stepsRetentionFailure(
		effective: SourcePolicySnapshot,
		retentionResults: List<RetentionAuthorityResult>,
	): SourcePolicyRevisionReconciliationFailure? {
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
		return when {
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
	}

	private suspend fun reconcileAmbientSteps(
		retentionFailure: SourcePolicyRevisionReconciliationFailure?,
	): AmbientStepsPolicyRevisionReconciliation = try {
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

	private fun completePolicyReconciliation(
		effective: SourcePolicySnapshot,
		startupGeneration: Long,
		retentionFailure: SourcePolicyRevisionReconciliationFailure?,
		ambientResult: AmbientStepsPolicyRevisionReconciliation,
	): SourcePolicyRevisionReconciliationResult {
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
			return result
		}
		return SourcePolicyRevisionReconciliationResult.Complete(effective).also { result ->
			lastCompletedStartupGeneration = startupGeneration
			publishReconciliationResult(result)
		}
	}

	private fun startupGenerationUnavailable(
		policyRevision: Long?,
		expectedStartupGeneration: Long,
	): SourcePolicyRevisionReconciliationResult.Retryable =
		SourcePolicyRevisionReconciliationResult.Retryable(
			SourcePolicyRevisionReconciliationDebt(
				policyRevision = policyRevision,
				failures = listOf(
					SourcePolicyRevisionReconciliationFailure.StartupGenerationUnavailable(
						expectedStartupGeneration,
					),
				),
			),
		).also(::publishReconciliationResult)

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
		const val REASON_SETUP_CHOICE_RECONCILIATION = "SETUP_CHOICE_RECONCILIATION"
		const val REASON_USER_AMBIENT_SETTINGS = "USER_AMBIENT_PRODUCT_SETTINGS"
		val APPROVED_AMBIENT_SOURCES = setOf(
			TrackingSourceComponent.LOCATION,
			TrackingSourceComponent.WIFI,
			TrackingSourceComponent.CELL,
		)
	}

	private data class SourcePolicyMutation(
		val revision: Long,
		val reconciliation: SourcePolicyRevisionReconciliationResult,
	)
}

/** Reuses the existing all-day Steps owner after retention approval is reissued or becomes unavailable. */
interface AmbientStepsPolicyRevisionReconciler {
	/** Reconciles selected all-day Steps against reissued retention authority. */
	suspend fun reconcileAfterRetentionReissue(): AmbientStepsPolicyRevisionReconciliation
	/** Retires all-day Steps ownership while retention remains unavailable. */
	suspend fun retireAfterRetentionDebt(): AmbientStepsPolicyRevisionReconciliation
}

/** Reconciles published source-policy revisions with retention and live provider ownership. */
interface SourcePolicyRevisionReconciliationCoordinator {
	val reconciliationState: StateFlow<SourcePolicyRevisionReconciliationState>

	/** Reconciles the current saved policy without replacing the user’s source choices. */
	suspend fun reconcileCurrentPolicyRevision(): SourcePolicyRevisionReconciliationResult

	/**
	 * Reconciles provider lifecycle while the caller already owns the exact Ready-generation
	 * operation. This avoids nested acquisition of the non-reentrant startup/deletion boundary.
	 */
	suspend fun reconcileCurrentPolicyRevisionWithinReadyOperation(
		expectedStartupGeneration: Long,
	): SourcePolicyRevisionReconciliationResult = reconcileCurrentPolicyRevision()
}

/**
 * Provider-free SourcePolicy bootstrap used while a durable deletion marker still closes runtime
 * admission. Completion grants no provider, demand, authorization, or Ambient lifecycle authority.
 */
fun interface SourcePolicyAuthorityBootstrapCoordinator {
	/** Prepares source policy only, without granting provider acquisition while deletion is closed. */
	suspend fun reconcileAuthorityForRetentionBootstrap():
		SourcePolicyRevisionReconciliationResult
}

/** Reports missing source authority rather than inventing successful reconciliation. */
object UnavailableSourcePolicyRevisionReconciliationCoordinator :
	SourcePolicyRevisionReconciliationCoordinator {
	override val reconciliationState: StateFlow<SourcePolicyRevisionReconciliationState> =
		MutableStateFlow(SourcePolicyRevisionReconciliationState.Uninitialized)

	/** Reconciles the current saved policy without replacing the user’s source choices. */
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

/** Observable progress for the current revision and its outstanding obligations. */
sealed interface SourcePolicyRevisionReconciliationState {
	/** No policy revision has completed reconciliation in this coordinator. */
	data object Uninitialized : SourcePolicyRevisionReconciliationState

	/** The named revision is currently being reconciled. */
	data class Reconciling(val policyRevision: Long) :
		SourcePolicyRevisionReconciliationState {
		init {
			require(policyRevision > 0L)
		}
	}

	/** The exact policy or all-day Steps reconciliation finished without outstanding obligations. */
	data class Complete(val policyRevision: Long) :
		SourcePolicyRevisionReconciliationState {
		init {
			require(policyRevision > 0L)
		}
	}

	/** Concrete policy-reconciliation failures retained for recovery. */
	data class Debt(val debt: SourcePolicyRevisionReconciliationDebt) :
		SourcePolicyRevisionReconciliationState
}

/** Current policy snapshot or typed outstanding recovery obligations. */
sealed interface SourcePolicyRevisionReconciliationResult {
	/** The exact policy or all-day Steps reconciliation finished without outstanding obligations. */
	data class Complete(val snapshot: SourcePolicySnapshot) :
		SourcePolicyRevisionReconciliationResult

	/** Every retained failure permits another attempt. */
	data class Retryable(val debt: SourcePolicyRevisionReconciliationDebt) :
		SourcePolicyRevisionReconciliationResult {
		init {
			require(debt.retryable)
		}
	}

	/** At least one retained integrity or lifecycle failure prevents ordinary retry. */
	data class Unverifiable(val debt: SourcePolicyRevisionReconciliationDebt) :
		SourcePolicyRevisionReconciliationResult {
		init {
			require(!debt.retryable)
		}
	}
}

/** Optional current policy revision and non-empty recovery failures. */
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

	/** A source-local Steps failure cannot invalidate another source's accepted recording choices. */
	val hasOnlyStepsFailures: Boolean
		get() = policyRevision != null && failures.all { failure ->
			when (failure) {
				is SourcePolicyRevisionReconciliationFailure.RetentionAuthority ->
					failure.failure.source == TrackingSourceComponent.STEPS
				is SourcePolicyRevisionReconciliationFailure.RetentionStateMismatch ->
					failure.result.source == TrackingSourceComponent.STEPS
				is SourcePolicyRevisionReconciliationFailure.AmbientStepsLifecycle -> true
				else -> false
			}
		}
}

/** Result of reusing the all-day Steps owner under the reconciled policy. */
sealed interface AmbientStepsPolicyRevisionReconciliation {
	/** The exact policy or all-day Steps reconciliation finished without outstanding obligations. */
	data object Complete : AmbientStepsPolicyRevisionReconciliation

	/** Every retained failure permits another attempt. */
	data class Retryable(val failureCode: String) :
		AmbientStepsPolicyRevisionReconciliation {
		init {
			require(failureCode.isNotBlank())
		}
	}

	/** At least one retained integrity or lifecycle failure prevents ordinary retry. */
	data class Unverifiable(val failureCode: String) :
		AmbientStepsPolicyRevisionReconciliation {
		init {
			require(failureCode.isNotBlank())
		}
	}
}

/** Concrete startup, policy, retention or all-day Steps ownership mismatch. */
sealed interface SourcePolicyRevisionReconciliationFailure {
	/** The expected startup/deletion generation is not ready. */
	data class StartupGenerationUnavailable(
		val expectedStartupGeneration: Long,
	) : SourcePolicyRevisionReconciliationFailure {
		init {
			require(expectedStartupGeneration >= 0L)
		}
	}

	/** No exact current source policy could be obtained. */
	data object SourcePolicyUnavailable : SourcePolicyRevisionReconciliationFailure

	/** The current policy could not be interpreted safely. */
	data class SourcePolicyInvalid(val reason: String) :
		SourcePolicyRevisionReconciliationFailure {
		init {
			require(reason.isNotBlank())
		}
	}

	/** The required source/purpose retention approval is unavailable. */
	data class RetentionAuthority(
		val failure: RetentionAuthorityResult.Unavailable,
	) : SourcePolicyRevisionReconciliationFailure

	/** Required retention reconciliation results are missing or duplicated. */
	data object RetentionResultSetInvalid : SourcePolicyRevisionReconciliationFailure

	/** Observed approval state differs from the state required by this policy. */
	data class RetentionStateMismatch(
		val result: RetentionAuthorityResult,
		val expectedState: RetentionAuthorityState,
	) : SourcePolicyRevisionReconciliationFailure

	/** The all-day Steps owner did not finish its required reconciliation. */
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
		is SourcePolicyRevisionReconciliationFailure.StartupGenerationUnavailable -> true
		SourcePolicyRevisionReconciliationFailure.SourcePolicyUnavailable -> true
		is SourcePolicyRevisionReconciliationFailure.SourcePolicyInvalid -> false
		is SourcePolicyRevisionReconciliationFailure.RetentionAuthority ->
			failure.reason !in NON_RETRYABLE_RETENTION_FAILURES
		SourcePolicyRevisionReconciliationFailure.RetentionResultSetInvalid -> false
		is SourcePolicyRevisionReconciliationFailure.RetentionStateMismatch -> false
		is SourcePolicyRevisionReconciliationFailure.AmbientStepsLifecycle ->
			result is AmbientStepsPolicyRevisionReconciliation.Retryable
	}

/** Preserves concrete failures when a settings edit cannot complete policy reconciliation. */
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

@Suppress("CyclomaticComplexMethod") // Keep this fixed field or enum projection together.
private fun TrackingParamsState.matchesSourceProjection(other: TrackingParamsState): Boolean =
	locationEnabled == other.locationEnabled &&
		activityEnabled == other.activityEnabled &&
		stepsEnabled == other.stepsEnabled &&
		wifiEnabled == other.wifiEnabled &&
		cellEnabled == other.cellEnabled &&
		barometerEnabled == other.barometerEnabled &&
		ambientLocationEnabled == other.ambientLocationEnabled &&
		locationProfileVersion == other.locationProfileVersion &&
		locationBalanceLevel == other.locationBalanceLevel &&
		balanceProfileVersion == other.balanceProfileVersion &&
		overallBalanceLevel == other.overallBalanceLevel &&
		sourceBalanceOverrides == other.sourceBalanceOverrides &&
		locationBackgroundMode == other.locationBackgroundMode &&
		locationMotionAssistanceEnabled == other.locationMotionAssistanceEnabled &&
		ambientStepsEnabled == other.ambientStepsEnabled &&
		ambientWifiEnabled == other.ambientWifiEnabled &&
		ambientCellEnabled == other.ambientCellEnabled &&
		backgroundWifiNotificationEnabled == other.backgroundWifiNotificationEnabled &&
		backgroundCellNotificationEnabled == other.backgroundCellNotificationEnabled &&
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
		locationProfileVersion = location.balanceProfileVersion,
		locationBalanceLevel = location.balanceLevel,
		balanceProfileVersion = location.balanceProfileVersion.takeIf { location.overallBalanceLevel != null },
		overallBalanceLevel = location.overallBalanceLevel,
		sourceBalanceOverrides = SourceBalanceOverrides(location.sourceBalanceOverride, activity.sourceBalanceOverride,
			steps.sourceBalanceOverride, pressure.sourceBalanceOverride, wifi.sourceBalanceOverride,
                cell.sourceBalanceOverride),
		locationBackgroundMode = location.locationBackgroundMode,
		locationMotionAssistanceEnabled = location.locationMotionAssistanceEnabled,
		ambientStepsEnabled = steps.enabled,
		ambientWifiEnabled = wifi.ambientConsentEpoch != null &&
			wifi.ambientPersistenceEligible,
		ambientCellEnabled = cell.ambientConsentEpoch != null &&
			cell.ambientPersistenceEligible,
		backgroundWifiNotificationEnabled = wifi.backgroundNotificationHostEnabled,
		backgroundCellNotificationEnabled = cell.backgroundNotificationHostEnabled,
		minTimeSeconds = requireNotNull(location.locationMinTimeSeconds),
		minDistanceMeters = requireNotNull(location.locationMinDistanceMeters),
		requiredAccuracyMeters = requireNotNull(location.locationRequiredAccuracyMeters),
		sourceCollectionSettings =
            if (location.overallBalanceLevel != null) sourceCollectionSettings else SourceCollectionSettings(
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
	backgroundWifiNotificationEnabled = false,
	backgroundCellNotificationEnabled = false,
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
    if (balanceProfileVersion != null) return copy(
        locationEnabled = if (source == TrackingSourceComponent.LOCATION) enabled else locationEnabled,
        activityEnabled = if (source == TrackingSourceComponent.ACTIVITY) enabled else activityEnabled,
        stepsEnabled = if (source == TrackingSourceComponent.STEPS) enabled else stepsEnabled,
		ambientStepsEnabled = if (source == TrackingSourceComponent.STEPS) enabled else ambientStepsEnabled,
        barometerEnabled = if (source == TrackingSourceComponent.PRESSURE) enabled else barometerEnabled,
        wifiEnabled = if (source == TrackingSourceComponent.WIFI) enabled else wifiEnabled,
        cellEnabled = if (source == TrackingSourceComponent.CELL) enabled else cellEnabled,
    )
	val nextFrequency = frequency(source).forPolicyEnabled(enabled)
	return withSourceFrequency(source, nextFrequency)
}

private fun TrackingParamsState.withSourceFrequency(
	source: TrackingSourceComponent,
	frequency: SourceCollectionFrequency,
): TrackingParamsState {
	require(balanceProfileVersion == null) { "Legacy frequency editing requires a legacy/custom configuration" }
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
		ambientStepsEnabled = if (source == TrackingSourceComponent.STEPS) enabled else ambientStepsEnabled,
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
	if (balanceProfileVersion != null) return copy(ambientStepsEnabled = stepsEnabled)
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
	return normalized.copy(ambientStepsEnabled = normalized.stepsEnabled)
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
