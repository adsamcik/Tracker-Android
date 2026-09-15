package com.adsamcik.tracker.tracker.api

import kotlinx.coroutines.flow.StateFlow

enum class AutomaticTrackingUnavailableReason(val stableCode: String) {
	CONTROL_RETENTION_POLICY_UNAVAILABLE("CONTROL_RETENTION_POLICY_UNAVAILABLE"),
}

sealed interface AutomaticTrackingOperationalAvailability {
	data object Ready : AutomaticTrackingOperationalAvailability

	data class Unavailable(
		val reason: AutomaticTrackingUnavailableReason,
	) : AutomaticTrackingOperationalAvailability

	val isOperational: Boolean
		get() = this is Ready
}

enum class AmbientTrackingSource {
	STEPS,
	LOCATION,
	WIFI,
	CELL,
}

enum class AmbientSourceOperationalState {
	READY,
	WAITING,
	PERMISSION_REQUIRED,
	UNAVAILABLE,
	DEGRADED,
}

enum class AmbientAcquisitionMechanism {
	HEALTH_CONNECT_MOBILE_STEPS,
	LOCAL_RECORDING_STEPS,
	PASSIVE_LOCATION,
	WIFI_SCAN_RESULTS,
	CELL_CHANGE_CALLBACKS,
}

enum class AmbientSourceUnavailableReason(val stableCode: String) {
	RETENTION_POLICY_UNAVAILABLE("AMBIENT_RETENTION_POLICY_UNAVAILABLE"),
	RECONCILIATION_PENDING("AMBIENT_RECONCILIATION_PENDING"),
	ROLLOUT_CONTAINED("AMBIENT_ROLLOUT_CONTAINED"),
	HEALTH_CONNECT_STEPS_PERMISSION_REQUIRED("HEALTH_CONNECT_STEPS_PERMISSION_REQUIRED"),
	HEALTH_CONNECT_BACKGROUND_PERMISSION_OPTIONAL("HEALTH_CONNECT_BACKGROUND_PERMISSION_OPTIONAL"),
	HEALTH_CONNECT_UNAVAILABLE("HEALTH_CONNECT_UNAVAILABLE"),
	HEALTH_CONNECT_UPDATE_REQUIRED("HEALTH_CONNECT_UPDATE_REQUIRED"),
	HEALTH_CONNECT_PROBE_FAILED("HEALTH_CONNECT_PROBE_FAILED"),
	LOCAL_RECORDING_ACTIVITY_PERMISSION_REQUIRED("LOCAL_RECORDING_ACTIVITY_PERMISSION_REQUIRED"),
	LOCAL_RECORDING_UNAVAILABLE("LOCAL_RECORDING_UNAVAILABLE"),
	BACKGROUND_LOCATION_PERMISSION_REQUIRED("BACKGROUND_LOCATION_PERMISSION_REQUIRED"),
	WIFI_SCAN_PERMISSION_REQUIRED("WIFI_SCAN_PERMISSION_REQUIRED"),
	CELL_SCAN_PERMISSION_REQUIRED("CELL_SCAN_PERMISSION_REQUIRED"),
	PLATFORM_UNAVAILABLE("AMBIENT_PLATFORM_UNAVAILABLE"),
	PROVIDER_UNAVAILABLE("AMBIENT_PROVIDER_UNAVAILABLE"),
}

data class AmbientSourceOperationalAvailability(
	val source: AmbientTrackingSource,
	val state: AmbientSourceOperationalState,
	val mechanism: AmbientAcquisitionMechanism? = null,
	val reason: AmbientSourceUnavailableReason? = null,
) {
	init {
		require((state == AmbientSourceOperationalState.READY) == (reason == null)) {
			"Ready ambient availability must not carry a failure reason"
		}
	}

	val isOperational: Boolean
		get() = state == AmbientSourceOperationalState.READY ||
			state == AmbientSourceOperationalState.DEGRADED

	companion object {
		fun retentionPolicyUnavailable(
			source: AmbientTrackingSource,
		): AmbientSourceOperationalAvailability = AmbientSourceOperationalAvailability(
			source = source,
			state = AmbientSourceOperationalState.UNAVAILABLE,
			reason = AmbientSourceUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
		)
	}
}

data class TrackingPurposeAvailabilitySnapshot(
	val automaticControl: AutomaticTrackingOperationalAvailability =
		AutomaticTrackingOperationalAvailability.Unavailable(
			AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
		),
	val ambientSources: Map<AmbientTrackingSource, AmbientSourceOperationalAvailability> =
		AmbientTrackingSource.entries.associateWith { source ->
			AmbientSourceOperationalAvailability.retentionPolicyUnavailable(source)
		},
) {
	init {
		require(ambientSources.keys == AmbientTrackingSource.entries.toSet()) {
			"Ambient availability must describe every approved ambient source"
		}
		require(ambientSources.all { (source, availability) -> source == availability.source }) {
			"Ambient availability keys must match their source values"
		}
	}

	companion object {
		val SAFE_DEFAULT = TrackingPurposeAvailabilitySnapshot()
	}
}

interface TrackingPurposeAvailabilityReader {
	/** Parent-owned runtime catalog; reading it must not itself probe or register a provider. */
	val availability: StateFlow<TrackingPurposeAvailabilitySnapshot>
}

interface TrackingPurposeAvailabilityReporter {
	/** Publishes only after the owning runtime has reconciled policy, permission, rollout, and provider. */
	fun reportAutomaticControl(availability: AutomaticTrackingOperationalAvailability)
	fun reportAmbientSource(availability: AmbientSourceOperationalAvailability)
}

sealed interface AmbientSourceReconciliationResult {
	data class Reconciled(
		val availability: AmbientSourceOperationalAvailability,
	) : AmbientSourceReconciliationResult {
		init {
			require(availability.isOperational)
		}
	}

	data class Unavailable(
		val availability: AmbientSourceOperationalAvailability,
	) : AmbientSourceReconciliationResult {
		init {
			require(!availability.isOperational)
		}
	}
}

fun interface AmbientSourceReconciliationCallback {
	/** Parent-owned lifecycle entry point. Settings UI never invokes this merely from preference state. */
	suspend fun reconcile(source: AmbientTrackingSource): AmbientSourceReconciliationResult
}
