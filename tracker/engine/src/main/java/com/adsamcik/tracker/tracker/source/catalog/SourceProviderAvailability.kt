package com.adsamcik.tracker.tracker.source.catalog

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.tracker.api.TrackingDecisionContainmentReason
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsCapability
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsPermission
import com.adsamcik.tracker.tracker.source.ambient.steps.AndroidAmbientStepsCapabilityResolver
import com.adsamcik.tracker.tracker.source.ambient.steps.HealthConnectAmbientStepsAvailability
import com.adsamcik.tracker.tracker.source.ambient.steps.LocalRecordingAmbientStepsAvailability
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.runtime.ClaimedSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.LocationDeviceState
import com.adsamcik.tracker.tracker.source.runtime.LocationDeviceStateProvider
import com.adsamcik.tracker.tracker.source.runtime.SourceCapabilities

fun interface SourceProviderAvailabilityReader {
	suspend fun read(): SourceProviderAvailability
}

sealed interface SourceProviderPermission {
	data class RuntimePermission(val source: SourceKind) : SourceProviderPermission
	data class AmbientStepsPermissionGrant(
		val permission: AmbientStepsPermission,
	) : SourceProviderPermission
}

sealed interface SourceProviderAvailabilityEvidence {
	data class Runtime(
		val source: SourceKind,
		val reasons: Set<SourceDegradedReason>,
	) : SourceProviderAvailabilityEvidence

	data class AmbientSteps(
		val healthConnect: HealthConnectAmbientStepsAvailability,
		val localRecording: LocalRecordingAmbientStepsAvailability,
	) : SourceProviderAvailabilityEvidence
}

sealed interface SourceProviderAvailability {
	data class Available(
		val degradedReasons: Set<SourceDegradedReason> = emptySet(),
	) : SourceProviderAvailability

	data class PermissionRequired(
		val permissions: Set<SourceProviderPermission>,
	) : SourceProviderAvailability {
		init {
			require(permissions.isNotEmpty())
		}
	}

	data class ProviderUnavailable(
		val evidence: SourceProviderAvailabilityEvidence,
	) : SourceProviderAvailability

	data class OsLimited(
		val evidence: SourceProviderAvailabilityEvidence,
	) : SourceProviderAvailability

	data class HardwareUnavailable(
		val evidence: SourceProviderAvailabilityEvidence,
	) : SourceProviderAvailability

	data class Contained(
		val reason: TrackingDecisionContainmentReason,
	) : SourceProviderAvailability
}

internal fun interface SourceProviderAvailabilityReaderFactory {
	fun create(
		source: TrackingSource,
		purpose: TrackingPurpose,
		runtime: ClaimedSourceRuntime<out SourcePlan>,
	): SourceProviderAvailabilityReader
}

internal class DefaultSourceProviderAvailabilityReaderFactory(
	private val ambientStepsCapabilityResolver: AndroidAmbientStepsCapabilityResolver,
	private val locationDeviceStateProvider: LocationDeviceStateProvider,
) : SourceProviderAvailabilityReaderFactory {
	override fun create(
		source: TrackingSource,
		purpose: TrackingPurpose,
		runtime: ClaimedSourceRuntime<out SourcePlan>,
	): SourceProviderAvailabilityReader {
		require(runtime.source == source.toRuntimeSourceKind())
		return when {
			source == TrackingSource.ACTIVITY && purpose == TrackingPurpose.CONTROL ->
				containedAvailability(
					TrackingDecisionContainmentReason.AUTO_005_CONTROL_EVIDENCE_UNRESOLVED,
				)
			source == TrackingSource.LOCATION && purpose == TrackingPurpose.AMBIENT_PRODUCT ->
				containedAvailability(
					TrackingDecisionContainmentReason.EXPANDED_AMBIENT_LOCATION_UNAVAILABLE,
				)
			source == TrackingSource.STEPS && purpose == TrackingPurpose.AMBIENT_PRODUCT ->
				SourceProviderAvailabilityReader {
					ambientStepsCapabilityResolver.resolve().toSourceProviderAvailability()
				}
			source == TrackingSource.LOCATION ->
				SourceProviderAvailabilityReader {
					locationDeviceStateProvider.snapshot().toSourceProviderAvailability()
				}
			else -> RuntimeSourceProviderAvailabilityReader(runtime)
		}
	}
}

internal class RuntimeSourceProviderAvailabilityReader(
	private val runtime: ClaimedSourceRuntime<out SourcePlan>,
) : SourceProviderAvailabilityReader {
	override suspend fun read(): SourceProviderAvailability =
		runtime.capabilities.value.toSourceProviderAvailability(runtime.source)
}

internal fun containedAvailability(
	reason: TrackingDecisionContainmentReason,
): SourceProviderAvailabilityReader = SourceProviderAvailabilityReader {
	SourceProviderAvailability.Contained(reason)
}

internal fun SourceCapabilities.toSourceProviderAvailability(
	source: SourceKind,
): SourceProviderAvailability {
	val reasons = if (!available && degradedReasons.isEmpty() && source in SENSOR_ONLY_SOURCES) {
		setOf(SourceDegradedReason.HARDWARE_UNAVAILABLE)
	} else {
		degradedReasons
	}
	val evidence = SourceProviderAvailabilityEvidence.Runtime(source, reasons)
	return when {
		SourceDegradedReason.HARDWARE_UNAVAILABLE in reasons ->
			SourceProviderAvailability.HardwareUnavailable(evidence)
		SourceDegradedReason.PERMISSION_MISSING in reasons ->
			SourceProviderAvailability.PermissionRequired(
				setOf(SourceProviderPermission.RuntimePermission(source)),
			)
		reasons.any(OS_LIMITED_REASONS::contains) ->
			SourceProviderAvailability.OsLimited(evidence)
		SourceDegradedReason.PROVIDER_UNAVAILABLE in reasons || !available ->
			SourceProviderAvailability.ProviderUnavailable(evidence)
		else -> SourceProviderAvailability.Available(reasons)
	}
}

internal fun LocationDeviceState.toSourceProviderAvailability(): SourceProviderAvailability {
	val reasons = buildSet {
		if (!locationFeatureAvailable) add(SourceDegradedReason.HARDWARE_UNAVAILABLE)
		if (!coarsePermission && !finePermission) add(SourceDegradedReason.PERMISSION_MISSING)
		if (!locationServicesEnabled) add(SourceDegradedReason.PROVIDER_UNAVAILABLE)
		if (!foregroundServiceLocationCapability) {
			add(SourceDegradedReason.FOREGROUND_CAPABILITY_MISSING)
		}
	}
	return SourceCapabilities(
		available = reasons.isEmpty(),
		batchingSupported = fusedProviderAvailable,
		flushSupported = fusedProviderAvailable,
		maximumBatchSize = null,
		minimumDelayMs = null,
		degradedReasons = reasons,
	).toSourceProviderAvailability(SourceKind.LOCATION)
}

internal fun AmbientStepsCapability.toSourceProviderAvailability(): SourceProviderAvailability =
	when (this) {
		is AmbientStepsCapability.ReadyForRegistration ->
			SourceProviderAvailability.Available()
		is AmbientStepsCapability.PermissionRequired ->
			SourceProviderAvailability.PermissionRequired(
				requiredPermissions.mapTo(linkedSetOf()) { permission ->
					SourceProviderPermission.AmbientStepsPermissionGrant(permission)
				},
			)
		is AmbientStepsCapability.Unavailable -> {
			val evidence = SourceProviderAvailabilityEvidence.AmbientSteps(
				healthConnect,
				localRecording,
			)
			if (
				healthConnect == HealthConnectAmbientStepsAvailability.PLATFORM_TOO_OLD ||
				healthConnect == HealthConnectAmbientStepsAvailability.EXTENSION_TOO_OLD
			) {
				SourceProviderAvailability.OsLimited(evidence)
			} else {
				SourceProviderAvailability.ProviderUnavailable(evidence)
			}
		}
	}

private val OS_LIMITED_REASONS = setOf(
	SourceDegradedReason.BACKGROUND_START_ILLEGAL,
	SourceDegradedReason.FOREGROUND_CAPABILITY_MISSING,
	SourceDegradedReason.PLATFORM_THROTTLED,
)

private val SENSOR_ONLY_SOURCES = setOf(
	SourceKind.STEPS,
	SourceKind.PRESSURE,
)
