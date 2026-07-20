package com.adsamcik.tracker.tracker.policy

/**
 * Inputs available to the tracker policy when recording a controlled-exploration decision.
 *
 * A passive, non-user-initiated run is only a candidate for exploration. A future non-zero
 * experiment must add the remaining EXP-01 gates (confirmed stationary state, permissions,
 * recent-fix age, thermal/battery state, and energy budget) before treating it as fully eligible.
 */
internal data class ControlledExplorationContext(
	val policy: TrackingPolicy,
	val isUserInitiated: Boolean,
)

/** Stable reason persisted with each controlled-exploration decision. */
internal enum class ControlledExplorationReason {
	PRODUCTION_DISABLED,
	NOT_ELIGIBLE,
}

/**
 * Auditable output of the controlled-exploration hook.
 *
 * This type deliberately exposes no acquisition operation. Selection can only be consumed after
 * an explicit integration is added, while production currently uses the zero-propensity policy.
 */
internal data class ControlledExplorationDecision(
	val eligible: Boolean,
	val propensity: Double,
	val selected: Boolean,
	val reason: ControlledExplorationReason,
) {
	init {
		require(propensity.isFinite() && propensity in 0.0..1.0) {
			"Controlled-exploration propensity must be finite and in [0, 1]"
		}
		require(!selected || eligible) {
			"An ineligible controlled-exploration decision cannot be selected"
		}
		require(!selected || propensity > 0.0) {
			"A zero-propensity controlled-exploration decision cannot be selected"
		}
	}
}

/** Policy seam for a future research-build exploration implementation. */
internal fun interface ControlledExplorationPolicy {
	fun decide(context: ControlledExplorationContext): ControlledExplorationDecision
}

/**
 * Production EXP-01 policy: deterministic, disabled, and incapable of selecting an acquisition.
 */
internal object ProductionControlledExplorationPolicy : ControlledExplorationPolicy {
	const val SELECTION_PROPENSITY: Double = 0.0

	override fun decide(context: ControlledExplorationContext): ControlledExplorationDecision {
		val eligible = !context.isUserInitiated && context.policy == TrackingPolicy.PASSIVE_LOW
		return ControlledExplorationDecision(
			eligible = eligible,
			propensity = SELECTION_PROPENSITY,
			selected = false,
			reason = if (eligible) {
				ControlledExplorationReason.PRODUCTION_DISABLED
			} else {
				ControlledExplorationReason.NOT_ELIGIBLE
			},
		)
	}
}
