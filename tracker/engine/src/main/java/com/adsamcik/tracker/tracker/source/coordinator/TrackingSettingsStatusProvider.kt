package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.battery.BatteryImpactEstimate
import com.adsamcik.tracker.tracker.source.battery.BatteryImpactEstimator
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Environment used for a settings preview without claiming that a runtime is registered. */
data class TrackingSettingsPreviewEnvironment(
	val planEnvironment: SourcePlanEnvironment,
	val resolutionContext: PlanResolutionContext,
)

enum class EffectiveSourceState {
	DISABLED,
	READY,
	NOT_TRACKING,
	APPLYING,
	ACTIVE,
	DEGRADED,
	BLOCKED,
	FAILED,
}

/** Payload-free source status safe for settings and local diagnostics surfaces. */
data class EffectiveSourceStatus(
	val source: SourceKind,
	val owner: SourceOwner?,
	val requestedFrequency: SourceCollectionFrequency,
	val requestedMode: String,
	val effectiveMode: String,
	val state: EffectiveSourceState,
	val reasonCodes: Set<String>,
	val desiredRevision: Long?,
	val appliedRevision: Long?,
)

data class TrackingSettingsPreview(
	val batteryEstimate: BatteryImpactEstimate,
	val sources: Map<SourceKind, EffectiveSourceStatus>,
	val requestedPlans: Map<SourceKind, SourcePlan>,
	val frequencyOptions: Map<SourceKind, Map<SourceCollectionFrequency, SourcePlan>>,
)

data class TrackingRuntimeStatus(
	val active: Boolean = false,
	val sourcePolicyRevision: Long? = null,
	val rolloutRevision: Long? = null,
	val planId: String? = null,
	val desiredRevision: Long? = null,
	val appliedRevision: Long? = null,
	val batteryEstimate: BatteryImpactEstimate? = null,
	val sources: Map<SourceKind, EffectiveSourceStatus> = emptyMap(),
	val requestedPlans: Map<SourceKind, SourcePlan> = emptyMap(),
	val failureCode: String? = null,
)

/** Read/write boundary between the engine's applied plans and settings diagnostics. */
interface TrackingSettingsStatusProvider {
	val runtimeStatus: StateFlow<TrackingRuntimeStatus>
	val telemetry: StateFlow<TrackingCoordinatorMetrics>

	fun preview(
		settings: TrackingParamsState,
		environment: TrackingSettingsPreviewEnvironment,
	): TrackingSettingsPreview

	fun publishActivePreview(
		settings: TrackingParamsState,
		rollout: TrackingRolloutState,
		inputs: SourceSessionPlanInputs,
	)

	fun publishResolved(
		settings: TrackingParamsState,
		rollout: TrackingRolloutState,
		resolved: ResolvedAcquisitionPlan,
	)

	fun publishApplied(applied: List<AppliedSourcePlan>)
	fun publishFailure(code: String)
	fun publishInactive()
}

@Singleton
class DefaultTrackingSettingsStatusProvider @Inject constructor(
	private val planFactory: SemanticAcquisitionPlanFactory,
	private val planResolver: SourcePlanResolver,
	private val batteryImpactEstimator: BatteryImpactEstimator,
	coordinatorTelemetry: TrackingCoordinatorTelemetry,
) : TrackingSettingsStatusProvider {
	private val mutableRuntimeStatus = MutableStateFlow(TrackingRuntimeStatus())
	override val runtimeStatus: StateFlow<TrackingRuntimeStatus> = mutableRuntimeStatus.asStateFlow()
	override val telemetry: StateFlow<TrackingCoordinatorMetrics> = coordinatorTelemetry.metrics

	override fun preview(
		settings: TrackingParamsState,
		environment: TrackingSettingsPreviewEnvironment,
	): TrackingSettingsPreview {
		val desired = planFactory.create(settings, 0L, 0L, environment.planEnvironment)
		val resolved = planResolver.resolve(desired, emptyList(), environment.resolutionContext)
		return TrackingSettingsPreview(
			batteryEstimate = batteryImpactEstimator.estimate(
				desired,
				comparisonBaselineId = "preset:${settings.presetName.lowercase()}",
			),
			sources = statuses(
				settings = settings,
				rollout = null,
				resolved = resolved,
				active = false,
			),
			requestedPlans = desired.plans,
			frequencyOptions = if (settings.advancedSourceControlsEnabled) {
				frequencyOptions(settings, environment)
			} else {
				emptyMap()
			},
		)
	}

	private fun frequencyOptions(
		settings: TrackingParamsState,
		environment: TrackingSettingsPreviewEnvironment,
	): Map<SourceKind, Map<SourceCollectionFrequency, SourcePlan>> {
		val plansByFrequency = SourceCollectionFrequency.entries.associateWith { frequency ->
			val current = settings.sourceCollectionSettings
			val optionSettings = settings.copy(
				sourceCollectionSettings = current.copy(
					location = frequency,
					activity = frequency,
					steps = frequency,
					pressure = frequency,
					wifi = frequency,
					cell = frequency,
				),
			)
			planFactory.create(optionSettings, 0L, 0L, environment.planEnvironment).plans
		}
		return SourceKind.entries.associateWith { source ->
			SourceCollectionFrequency.entries.associateWith { frequency ->
				checkNotNull(plansByFrequency.getValue(frequency)[source])
			}
		}
	}

	override fun publishActivePreview(
		settings: TrackingParamsState,
		rollout: TrackingRolloutState,
		inputs: SourceSessionPlanInputs,
	) {
		val desired = planFactory.create(settings, 0L, 0L, inputs.environment)
		publishResolved(
			settings,
			rollout,
			planResolver.resolve(desired, inputs.demands, inputs.resolutionContext),
		)
	}

	override fun publishResolved(
		settings: TrackingParamsState,
		rollout: TrackingRolloutState,
		resolved: ResolvedAcquisitionPlan,
	) {
		val effectivePlan = AcquisitionPlanRevision(
			revision = resolved.desired.revision,
			planId = "${resolved.desired.planId}-effective",
			createdAtMs = resolved.desired.createdAtMs,
			plans = resolved.applicablePlans,
			sourcePolicyRevision = resolved.desired.sourcePolicyRevision,
		)
		mutableRuntimeStatus.value = TrackingRuntimeStatus(
			active = true,
			sourcePolicyRevision = resolved.desired.sourcePolicyRevision,
			rolloutRevision = rollout.revision,
			planId = resolved.desired.planId,
			desiredRevision = resolved.desired.revision.takeIf { it > 0L },
			appliedRevision = null,
			batteryEstimate = batteryImpactEstimator.estimate(
				effectivePlan,
				comparisonBaselineId = "requested:${resolved.desired.planId}",
			),
			sources = statuses(settings, rollout, resolved, active = true),
			requestedPlans = resolved.desired.plans,
		)
	}

	override fun publishApplied(applied: List<AppliedSourcePlan>) {
		if (applied.isEmpty()) return
		mutableRuntimeStatus.update { current ->
			val updated = current.sources.toMutableMap()
			applied.forEach { result ->
				val previous = updated[result.source] ?: return@forEach
				updated[result.source] = previous.copy(
					state = when {
						previous.requestedFrequency == SourceCollectionFrequency.OFF ->
							EffectiveSourceState.DISABLED
						previous.state == EffectiveSourceState.BLOCKED -> EffectiveSourceState.BLOCKED
						previous.state == EffectiveSourceState.DEGRADED -> EffectiveSourceState.DEGRADED
						else -> when (result.status) {
						SourceApplyStatus.APPLIED -> if (result.degradedReasons.isEmpty()) {
							EffectiveSourceState.ACTIVE
						} else {
							EffectiveSourceState.DEGRADED
						}
						SourceApplyStatus.DEGRADED -> EffectiveSourceState.DEGRADED
						SourceApplyStatus.ROLLED_BACK,
						SourceApplyStatus.BLOCKED,
						-> EffectiveSourceState.BLOCKED
						SourceApplyStatus.FAILED -> EffectiveSourceState.FAILED
						}
					},
					reasonCodes = previous.reasonCodes + result.degradedReasons.map { it.name },
					desiredRevision = result.desiredRevision,
					appliedRevision = result.appliedRevision,
				)
			}
			current.copy(
				appliedRevision = applied.mapNotNull(AppliedSourcePlan::appliedRevision).maxOrNull(),
				sources = updated,
				failureCode = null,
			)
		}
	}

	override fun publishFailure(code: String) {
		mutableRuntimeStatus.update { current ->
			current.copy(
				failureCode = code,
				sources = current.sources.mapValues { (_, status) ->
					if (status.state != EffectiveSourceState.APPLYING) status else status.copy(
						state = EffectiveSourceState.FAILED,
						reasonCodes = status.reasonCodes + code,
					)
				},
			)
		}
	}

	override fun publishInactive() {
		mutableRuntimeStatus.update { current ->
			current.copy(
				active = false,
				appliedRevision = null,
				sources = current.sources.mapValues { (_, status) ->
					status.copy(
						state = if (status.requestedFrequency == SourceCollectionFrequency.OFF) {
							EffectiveSourceState.DISABLED
						} else {
							EffectiveSourceState.NOT_TRACKING
						},
						appliedRevision = null,
					)
				},
			)
		}
	}

	private fun statuses(
		settings: TrackingParamsState,
		rollout: TrackingRolloutState?,
		resolved: ResolvedAcquisitionPlan,
		active: Boolean,
	): Map<SourceKind, EffectiveSourceStatus> = SourceKind.entries.associateWith { source ->
		val desired = checkNotNull(resolved.desired.plans[source])
		val effective = checkNotNull(resolved.applicablePlans[source])
		val reasons = resolved.degradedReasons[source].orEmpty()
		val frequency = settings.frequency(source)
		val blocked = reasons.any { reason ->
			reason.name in BLOCKING_REASONS
		}
		val owner = rollout?.sourceOwners?.get(source)
		EffectiveSourceStatus(
			source = source,
			owner = owner,
			requestedFrequency = frequency,
			requestedMode = desired.presentationCode(),
			effectiveMode = effective.presentationCode(),
			state = when {
				!desired.enabled -> EffectiveSourceState.DISABLED
				blocked -> EffectiveSourceState.BLOCKED
				reasons.isNotEmpty() -> EffectiveSourceState.DEGRADED
				!active -> EffectiveSourceState.READY
				owner == SourceOwner.EVENT -> EffectiveSourceState.APPLYING
				else -> EffectiveSourceState.FAILED
			},
			reasonCodes = reasons.mapTo(linkedSetOf()) { it.name },
			desiredRevision = resolved.desired.revision.takeIf { it > 0L },
			appliedRevision = null,
		)
	}

	private fun TrackingParamsState.frequency(source: SourceKind): SourceCollectionFrequency = when (source) {
		SourceKind.LOCATION -> sourceCollectionSettings.location
		SourceKind.ACTIVITY -> sourceCollectionSettings.activity
		SourceKind.STEPS -> sourceCollectionSettings.steps
		SourceKind.PRESSURE -> sourceCollectionSettings.pressure
		SourceKind.WIFI -> sourceCollectionSettings.wifi
		SourceKind.CELL -> sourceCollectionSettings.cell
	}

	private fun SourcePlan.presentationCode(): String = when (this) {
		is LocationPlan -> mode.name
		is ActivityPlan -> mode.name
		is StepsPlan -> when {
			!enabled -> "OFF"
			movementPolicyNeedsLowLatency -> "RESPONSIVE"
			maximumReportLatencyMs >= 300_000L -> "BATCHED"
			else -> "BALANCED"
		}
		is PressurePlan -> when {
			!enabled -> "OFF"
			hardwareSamplePeriodMicros <= 100_000 -> "RESPONSIVE"
			maximumReportLatencyMicros >= 60_000_000 -> "BATCHED"
			else -> "BALANCED"
		}
		is WifiPlan -> mode.name
		is CellPlan -> mode.name
	}

	private companion object {
		val BLOCKING_REASONS = setOf(
			"PERMISSION_MISSING",
			"PROVIDER_UNAVAILABLE",
			"HARDWARE_UNAVAILABLE",
			"BACKGROUND_START_ILLEGAL",
			"FOREGROUND_CAPABILITY_MISSING",
		)
	}
}
