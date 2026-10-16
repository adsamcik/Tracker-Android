package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.api.TrackingStartFailureDisposition
import com.adsamcik.tracker.tracker.source.catalog.SourceAvailabilityTier
import com.adsamcik.tracker.tracker.source.catalog.SourceCatalogAvailability
import com.adsamcik.tracker.tracker.source.catalog.SourceProviderAvailability
import com.adsamcik.tracker.tracker.source.catalog.SourceProviderAvailabilityEvidence
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan

internal data class CatalogStartPrerequisiteFailure(
	val code: String,
	val disposition: TrackingStartFailureDisposition,
)

internal sealed interface CatalogSourcePlanDecision {
	val requestedPlan: SourcePlan

	data class Disabled(
		override val requestedPlan: SourcePlan,
	) : CatalogSourcePlanDecision

	data class Accepted(
		override val requestedPlan: SourcePlan,
		val effectivePlan: SourcePlan,
		val degradedReasons: Set<SourceDegradedReason>,
	) : CatalogSourcePlanDecision {
		init {
			require(requestedPlan.enabled)
			require(effectivePlan.enabled)
			require(requestedPlan.source == effectivePlan.source)
			require(requestedPlan.revision == effectivePlan.revision)
		}
	}

	/**
	 * A retryably unavailable source that is already running keeps its exact runtime authority.
	 *
	 * This is not permission to call the provider with a reconstructed plan. The enclosing
	 * reconfiguration remains deferred until availability changes or the source is removed.
	 */
	data class CarriedForward(
		override val requestedPlan: SourcePlan,
		val effectivePlan: SourcePlan,
		val reasons: Set<SourceDegradedReason>,
		val failure: CatalogStartPrerequisiteFailure,
	) : CatalogSourcePlanDecision {
		init {
			require(requestedPlan.enabled)
			require(effectivePlan.enabled)
			require(requestedPlan.source == effectivePlan.source)
			require(requestedPlan.revision == effectivePlan.revision)
			require(reasons.isNotEmpty())
			require(failure.disposition == TrackingStartFailureDisposition.RETRYABLE)
		}
	}

	data class Blocked(
		override val requestedPlan: SourcePlan,
		val availability: SourceCatalogAvailability?,
		val reasons: Set<SourceDegradedReason>,
		val failure: CatalogStartPrerequisiteFailure,
	) : CatalogSourcePlanDecision {
		init {
			require(requestedPlan.enabled)
			require(reasons.isNotEmpty())
		}
	}
}

internal data class CatalogPlanDecisions(
	val bySource: Map<SourceKind, CatalogSourcePlanDecision>,
) {
	val acceptedSources: Set<SourceKind> = bySource.values
		.filter { decision ->
			decision is CatalogSourcePlanDecision.Accepted ||
				decision is CatalogSourcePlanDecision.CarriedForward
		}
		.mapTo(linkedSetOf()) { decision -> decision.requestedPlan.source }

	fun getValue(source: SourceKind): CatalogSourcePlanDecision = bySource.getValue(source)

	fun failureIfNoAcceptedSource(): CatalogStartPrerequisiteFailure? {
		val enabled = bySource.values.filterNot { it is CatalogSourcePlanDecision.Disabled }
		if (enabled.any { decision ->
				decision is CatalogSourcePlanDecision.Accepted ||
					decision is CatalogSourcePlanDecision.CarriedForward
		}) {
			return null
		}
		return enabled.filterIsInstance<CatalogSourcePlanDecision.Blocked>()
			.minByOrNull { decision -> decision.requestedPlan.source.stableCode }
			?.failure
	}

	fun retryableFailure(): CatalogReconfigurationRetryDebt? {
		val retryable = bySource.values
			.filterIsInstance<CatalogSourcePlanDecision.Blocked>()
			.filter { decision ->
				decision.failure.disposition == TrackingStartFailureDisposition.RETRYABLE
			}
		val failure = retryable.minByOrNull { decision ->
			decision.requestedPlan.source.stableCode
		} ?: return null
		return CatalogReconfigurationRetryDebt(
			failure = failure.failure,
			sources = retryable.mapTo(linkedSetOf()) { decision ->
				decision.requestedPlan.source
			},
		)
	}

	fun constrainedToAcceptedSources(
		acceptedSources: Set<SourceKind>,
	): CatalogPlanDecisions = CatalogPlanDecisions(
		bySource.mapValues { (source, decision) ->
			if (decision !is CatalogSourcePlanDecision.Accepted || source in acceptedSources) {
				decision
			} else {
				CatalogSourcePlanDecision.Blocked(
					requestedPlan = decision.requestedPlan,
					availability = null,
					reasons = setOf(SourceDegradedReason.CATALOG_NOT_ACCEPTED),
					failure = CatalogStartPrerequisiteFailure(
						"SOURCE_CATALOG_${source.name}_SESSION_CAPTURE_NOT_ACCEPTED",
						TrackingStartFailureDisposition.TERMINAL,
					),
				)
			}
		},
	)

	val carriedForwardSources: Set<SourceKind>
		get() = bySource.values
			.filterIsInstance<CatalogSourcePlanDecision.CarriedForward>()
			.mapTo(linkedSetOf()) { decision -> decision.requestedPlan.source }
}

internal data class CatalogReconfigurationRetryDebt(
	val failure: CatalogStartPrerequisiteFailure,
	val sources: Set<SourceKind>,
) {
	init {
		require(failure.disposition == TrackingStartFailureDisposition.RETRYABLE)
		require(sources.isNotEmpty())
	}
}

internal data class CatalogReconfigurationTransition(
	val plan: AcquisitionPlanRevision,
	val decisions: CatalogPlanDecisions,
	val deferredSources: Set<SourceKind>,
	val retryableDebt: CatalogReconfigurationRetryDebt?,
)

internal fun CatalogPlanDecisions.forReconfiguration(
	requestedPlan: AcquisitionPlanRevision,
	currentAppliedPlans: Map<SourceKind, SourcePlan>,
): CatalogReconfigurationTransition {
	val deferredSources = linkedSetOf<SourceKind>()
	val resolvedDecisions = requestedPlan.plans.values
		.sortedBy { plan -> plan.source.stableCode }
		.associate { requested ->
			val catalogDecision = getValue(requested.source)
			val resolved = if (
				catalogDecision is CatalogSourcePlanDecision.Blocked &&
				catalogDecision.failure.disposition == TrackingStartFailureDisposition.RETRYABLE
			) {
				val current = currentAppliedPlans[requested.source]
				?.takeIf(SourcePlan::enabled)
				?.withRevision(requestedPlan.revision)
				when {
					current == null -> {
						deferredSources += requested.source
						CatalogSourcePlanDecision.Disabled(requested.disabledForCatalog())
					}
					else -> {
						deferredSources += requested.source
						CatalogSourcePlanDecision.CarriedForward(
							requestedPlan = requested,
							effectivePlan = current,
							reasons = catalogDecision.reasons,
							failure = catalogDecision.failure,
						)
					}
				}
			} else {
				catalogDecision
			}
			requested.source to resolved
		}
	val retryableDebt = deferredSources
		.map { source -> getValue(source) as CatalogSourcePlanDecision.Blocked }
		.minByOrNull { decision -> decision.requestedPlan.source.stableCode }
		?.let { decision ->
			CatalogReconfigurationRetryDebt(
				failure = decision.failure,
				sources = deferredSources,
			)
		}
	return CatalogReconfigurationTransition(
		plan = requestedPlan,
		decisions = CatalogPlanDecisions(resolvedDecisions),
		deferredSources = deferredSources,
		retryableDebt = retryableDebt,
	)
}

internal fun CatalogReconfigurationTransition.matchesCurrentEffectivePlans(
	currentAppliedPlans: Map<SourceKind, SourcePlan>,
): Boolean {
	val current = currentAppliedPlans.values
		.filter(SourcePlan::enabled)
		.associateBy(SourcePlan::source)
	val effective = decisions.bySource.values.mapNotNull { decision ->
		when (decision) {
			is CatalogSourcePlanDecision.Accepted -> decision.effectivePlan
			is CatalogSourcePlanDecision.CarriedForward -> decision.effectivePlan
			is CatalogSourcePlanDecision.Blocked,
			is CatalogSourcePlanDecision.Disabled,
			-> null
		}
	}.filter(SourcePlan::enabled).associateBy(SourcePlan::source)
	if (current.keys != effective.keys) {
		return false
	}
	return current.all { (source, plan) ->
		plan.withRevision(0L) == effective.getValue(source).withRevision(0L)
	}
}

internal fun SourceActionExecution.acceptedEffectivePlanOrNull(plan: SourcePlan): SourcePlan? =
	plan.takeIf {
		applied.source == plan.source &&
			applied.appliedRevision == plan.revision &&
			applied.status in setOf(SourceApplyStatus.APPLIED, SourceApplyStatus.DEGRADED)
	}

internal fun SourcePlan.withRevision(revision: Long): SourcePlan = when (this) {
	is LocationPlan -> copy(revision = revision)
	is ActivityPlan -> copy(revision = revision)
	is StepsPlan -> copy(revision = revision)
	is PressurePlan -> copy(revision = revision)
	is WifiPlan -> copy(revision = revision)
	is CellPlan -> copy(revision = revision)
}

internal fun SourceCatalogAvailability.toCatalogPlanDecision(
	requestedPlan: SourcePlan,
): CatalogSourcePlanDecision = when (this) {
	is SourceCatalogAvailability.Unsupported -> CatalogSourcePlanDecision.Blocked(
		requestedPlan = requestedPlan,
		availability = this,
		reasons = setOf(SourceDegradedReason.CATALOG_UNSUPPORTED),
		failure = requireNotNull(toStartPrerequisiteFailure(requestedPlan.source)),
	)
	is SourceCatalogAvailability.Executable -> when (val result = availability) {
		is SourceProviderAvailability.Available -> CatalogSourcePlanDecision.Accepted(
			requestedPlan = requestedPlan,
			effectivePlan = requestedPlan,
			degradedReasons = result.degradedReasons,
		)
		is SourceProviderAvailability.Degraded -> CatalogSourcePlanDecision.Accepted(
			requestedPlan = requestedPlan,
			effectivePlan = result.effectivePlan,
			degradedReasons = result.evidence.reasons(),
		)
		else -> CatalogSourcePlanDecision.Blocked(
			requestedPlan = requestedPlan,
			availability = this,
			reasons = result.blockingReasons(),
			failure = requireNotNull(toStartPrerequisiteFailure(requestedPlan.source)),
		)
	}
}

private fun SourceProviderAvailability.blockingReasons():
	Set<SourceDegradedReason> = when (this) {
	is SourceProviderAvailability.PermissionRequired -> setOf(
		SourceDegradedReason.PERMISSION_MISSING,
	)
	is SourceProviderAvailability.ProviderUnavailable ->
		evidence.reasons().ifEmpty {
			setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE)
		}
	is SourceProviderAvailability.OsLimited ->
		evidence.reasons().ifEmpty {
			setOf(SourceDegradedReason.PLATFORM_THROTTLED)
		}
	is SourceProviderAvailability.HardwareUnavailable ->
		evidence.reasons().ifEmpty {
			setOf(SourceDegradedReason.HARDWARE_UNAVAILABLE)
		}
	is SourceProviderAvailability.Contained -> setOf(
		SourceDegradedReason.CATALOG_CONTAINED,
	)
	is SourceProviderAvailability.Available,
	is SourceProviderAvailability.Degraded,
	-> error("Available source has no blocking reasons")
}

private fun SourceProviderAvailabilityEvidence.reasons():
	Set<SourceDegradedReason> = when (this) {
	is SourceProviderAvailabilityEvidence.Runtime -> reasons
	is SourceProviderAvailabilityEvidence.Location -> reasons
	is SourceProviderAvailabilityEvidence.AmbientSteps -> emptySet()
}

internal fun SessionStartOrigin.toSourceAvailabilityTier(): SourceAvailabilityTier = when (this) {
	SessionStartOrigin.MANUAL_FOREGROUND_START -> SourceAvailabilityTier.MANUAL_FOREGROUND_START
	SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
	SessionStartOrigin.RECOVERY,
	-> SourceAvailabilityTier.AUTOMATIC_BACKGROUND_START
	SessionStartOrigin.POLICY_RECONCILIATION -> SourceAvailabilityTier.SESSION_ALREADY_FOREGROUND
}

// Exhaustive catalog-status conversion to a start failure and its retry disposition.
@Suppress("CyclomaticComplexMethod")
internal fun SourceCatalogAvailability.toStartPrerequisiteFailure(
	source: SourceKind,
): CatalogStartPrerequisiteFailure? = when (this) {
	is SourceCatalogAvailability.Unsupported -> CatalogStartPrerequisiteFailure(
		code = "SOURCE_CATALOG_${source.name}_${purpose.name}_UNSUPPORTED",
		disposition = TrackingStartFailureDisposition.TERMINAL,
	)
	is SourceCatalogAvailability.Executable -> {
		val code = when (availability) {
			is SourceProviderAvailability.Available,
			is SourceProviderAvailability.Degraded,
			-> null
			is SourceProviderAvailability.PermissionRequired ->
				"SOURCE_CATALOG_${source.name}_SESSION_CAPTURE_PERMISSION_REQUIRED"
			is SourceProviderAvailability.ProviderUnavailable ->
				"SOURCE_CATALOG_${source.name}_SESSION_CAPTURE_PROVIDER_UNAVAILABLE"
			is SourceProviderAvailability.OsLimited ->
				"SOURCE_CATALOG_${source.name}_SESSION_CAPTURE_OS_LIMITED"
			is SourceProviderAvailability.HardwareUnavailable ->
				"SOURCE_CATALOG_${source.name}_SESSION_CAPTURE_HARDWARE_UNAVAILABLE"
			is SourceProviderAvailability.Contained ->
				"SOURCE_CATALOG_${source.name}_SESSION_CAPTURE_CONTAINED"
		}
		code?.let {
			CatalogStartPrerequisiteFailure(
				code = it,
				disposition = when (availability) {
					is SourceProviderAvailability.ProviderUnavailable,
					is SourceProviderAvailability.OsLimited,
					-> TrackingStartFailureDisposition.RETRYABLE
					else -> TrackingStartFailureDisposition.TERMINAL
				},
			)
		}
	}
}

internal fun failedApplied(plan: SourcePlan, elapsedNanos: Long, status: SourceApplyStatus) = AppliedSourcePlan(
	desiredRevision = plan.revision,
	appliedRevision = null,
	source = plan.source,
	sourceInstanceId = null,
	registrationGeneration = null,
	appliedAtElapsedRealtimeNanos = elapsedNanos,
	status = status,
)

internal fun disabledApplied(plan: SourcePlan, elapsedNanos: Long) = AppliedSourcePlan(
	desiredRevision = plan.revision,
	appliedRevision = plan.revision,
	source = plan.source,
	sourceInstanceId = null,
	registrationGeneration = null,
	appliedAtElapsedRealtimeNanos = elapsedNanos,
	status = SourceApplyStatus.APPLIED,
)

internal fun CatalogSourcePlanDecision.Blocked.blockedApplied(
	elapsedNanos: Long,
): AppliedSourcePlan = failedApplied(
	requestedPlan,
	elapsedNanos,
	SourceApplyStatus.BLOCKED,
).copy(degradedReasons = reasons)

internal fun CatalogSourcePlanDecision.Blocked.toBlockedExecution(
	elapsedNanos: Long,
): SourceActionExecution = SourceActionExecution(
	applied = blockedApplied(elapsedNanos),
	status = LifecycleActionStatus.TERMINAL_FAILURE,
	failureCode = failure.code,
)

internal fun SourceActionExecution.withCatalogDecision(
	decision: CatalogSourcePlanDecision.Accepted,
): SourceActionExecution {
	if (decision.degradedReasons.isEmpty()) {
		return this
	}
	return copy(
		applied = applied.copy(
			status = if (applied.status == SourceApplyStatus.APPLIED) {
				SourceApplyStatus.DEGRADED
			} else {
				applied.status
			},
			degradedReasons = applied.degradedReasons + decision.degradedReasons,
		),
	)
}

internal fun SourcePlan.disabledForCatalog(): SourcePlan = when (this) {
	is LocationPlan -> copy(mode = LocationMode.DISABLED)
	is ActivityPlan -> copy(mode = ActivityMode.OFF)
	is StepsPlan -> copy(enabled = false)
	is PressurePlan -> copy(enabled = false)
	is WifiPlan -> copy(mode = WifiMode.OFF)
	is CellPlan -> copy(mode = CellMode.OFF)
}

