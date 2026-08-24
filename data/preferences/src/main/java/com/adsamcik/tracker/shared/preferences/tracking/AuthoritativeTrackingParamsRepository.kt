package com.adsamcik.tracker.shared.preferences.tracking

import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
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
 */
class AuthoritativeTrackingParamsRepository(
	private val legacy: TrackingParamsRepository,
	private val sourcePolicyRepository: SourcePolicyRepository,
	private val applicationScope: CoroutineScope,
	private val trackingStartupGate: TrackingStartupGate,
) : TrackingParamsRepository {
	private val mutationMutex = Mutex()
	private val scheduledMirrorRepairs = ConcurrentHashMap.newKeySet<Long>()

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
					emit(legacyState.withPolicy(snapshot))
					return@transformLatest
				} catch (error: Exception) {
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
				val projection = legacyState.withPolicy(authority.snapshot)
				emit(projection)
				scheduleLegacyMirrorRepair(legacyState, authority.snapshot)
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
		val effectiveRevision = mutationMutex.withLock {
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
			val authoritativeProjection = requested.withPolicy(effective)
			legacy.update { authoritativeProjection }
			effective.revision
		}
		data.first { state -> state.sourcePolicyRevision == effectiveRevision }
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

	private companion object {
		const val BOOTSTRAP_RETRY_DELAY_MS = 250L
		const val AUTHORITY_RETRY_DELAY_MS = 250L
		const val MIRROR_RETRY_DELAY_MS = 250L
		const val REASON_USER_SETTINGS = "USER_SOURCE_SETTINGS"
	}
}

private fun TrackingParamsState.matchesSourceProjection(other: TrackingParamsState): Boolean =
	locationEnabled == other.locationEnabled &&
		activityEnabled == other.activityEnabled &&
		stepsEnabled == other.stepsEnabled &&
		wifiEnabled == other.wifiEnabled &&
		cellEnabled == other.cellEnabled &&
		barometerEnabled == other.barometerEnabled &&
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
