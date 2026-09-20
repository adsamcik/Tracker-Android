package com.adsamcik.tracker.tracker.source.catalog

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
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
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.runtime.AndroidConnectivityDeviceStateProvider
import com.adsamcik.tracker.tracker.source.runtime.CellPrerequisiteEvaluator
import com.adsamcik.tracker.tracker.source.runtime.LocationDeviceStateProvider
import com.adsamcik.tracker.tracker.source.runtime.LocationPlanApplication
import com.adsamcik.tracker.tracker.source.runtime.LocationPlanApplicationStatus
import com.adsamcik.tracker.tracker.source.runtime.LocationPrerequisiteEvaluator
import com.adsamcik.tracker.tracker.source.runtime.LocationStartContext
import com.adsamcik.tracker.tracker.source.runtime.SourceCapabilities
import com.adsamcik.tracker.tracker.source.runtime.WifiPrerequisiteEvaluator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

fun interface SourceProviderAvailabilityReader {
	suspend fun read(request: SourceAvailabilityRequest): SourceProviderAvailability
}

enum class SourceAvailabilityTier {
	SESSION_ALREADY_FOREGROUND,
	MANUAL_FOREGROUND_START,
	AUTOMATIC_BACKGROUND_START,
	AMBIENT_REGISTRATION,
}

data class SourceAvailabilityRequest(
	val source: SourceKind,
	val purpose: TrackingPurpose,
	val plan: SourcePlan,
	val tier: SourceAvailabilityTier,
) {
	init {
		require(plan.source == source)
	}
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

	data class Location(
		val requestedBackend: LocationBackend,
		val effectiveBackend: LocationBackend,
		val reasons: Set<SourceDegradedReason>,
	) : SourceProviderAvailabilityEvidence
}

sealed interface SourceProviderAvailability {
	data class Available(
		val degradedReasons: Set<SourceDegradedReason> = emptySet(),
	) : SourceProviderAvailability

	data class Degraded(
		val effectivePlan: SourcePlan,
		val evidence: SourceProviderAvailabilityEvidence,
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

sealed interface SourceCatalogAvailability {
	data class Executable(
		val availability: SourceProviderAvailability,
	) : SourceCatalogAvailability

	data class Unsupported(
		val source: TrackingSource,
		val purpose: TrackingPurpose,
		val reason: UnsupportedSourcePurposeReason,
	) : SourceCatalogAvailability
}

data class ActivityRecognitionSourceState(
	val permissionGranted: Boolean,
	val providerAvailable: Boolean,
)

fun interface ActivityRecognitionSourceStateProvider {
	fun snapshot(): ActivityRecognitionSourceState
}

@Singleton
class AndroidActivityRecognitionSourceStateProvider @Inject constructor(
	@ApplicationContext private val context: Context,
) : ActivityRecognitionSourceStateProvider {
	override fun snapshot(): ActivityRecognitionSourceState = ActivityRecognitionSourceState(
		permissionGranted = context.hasActivityPermission,
		providerAvailable = Assist.isPlayServicesAvailable(context),
	)
}

internal fun interface SourceProviderAvailabilityReaderFactory {
	fun create(
		source: TrackingSource,
		purpose: TrackingPurpose,
	): SourceProviderAvailabilityReader
}

internal class DefaultSourceProviderAvailabilityReaderFactory(
	private val ambientStepsCapabilityResolver: AndroidAmbientStepsCapabilityResolver,
	private val locationDeviceStateProvider: LocationDeviceStateProvider,
	private val locationPrerequisiteEvaluator: LocationPrerequisiteEvaluator,
	private val activityRecognitionSourceStateProvider: ActivityRecognitionSourceStateProvider,
	private val sensorSourceStateProvider: SensorSourceStateProvider,
	private val connectivityDeviceStateProvider: AndroidConnectivityDeviceStateProvider,
) : SourceProviderAvailabilityReaderFactory {
	override fun create(
		source: TrackingSource,
		purpose: TrackingPurpose,
	): SourceProviderAvailabilityReader {
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
				SourceProviderAvailabilityReader { _ ->
					ambientStepsCapabilityResolver.resolve().toSourceProviderAvailability()
				}
			source == TrackingSource.LOCATION ->
				LocationSourceProviderAvailabilityReader(
					locationDeviceStateProvider,
					locationPrerequisiteEvaluator,
				)
			source == TrackingSource.ACTIVITY && purpose == TrackingPurpose.SESSION_CAPTURE ->
				ActivitySessionSourceProviderAvailabilityReader(activityRecognitionSourceStateProvider)
			source == TrackingSource.STEPS && purpose == TrackingPurpose.SESSION_CAPTURE ->
				StepsSessionSourceProviderAvailabilityReader(
					activityRecognitionSourceStateProvider,
					sensorSourceStateProvider,
				)
			source == TrackingSource.PRESSURE ->
				SensorSourceProviderAvailabilityReader(
					SourceKind.PRESSURE,
					sensorSourceStateProvider,
				)
			source == TrackingSource.WIFI ->
				WifiSourceProviderAvailabilityReader(connectivityDeviceStateProvider)
			source == TrackingSource.CELL ->
				CellSourceProviderAvailabilityReader(connectivityDeviceStateProvider)
			else -> error("No availability reader for $source ${purpose.stableName}")
		}
	}
}

internal class ActivitySessionSourceProviderAvailabilityReader(
	private val stateProvider: ActivityRecognitionSourceStateProvider,
) : SourceProviderAvailabilityReader {
	override suspend fun read(request: SourceAvailabilityRequest): SourceProviderAvailability {
		require(request.source == SourceKind.ACTIVITY)
		require(request.purpose == TrackingPurpose.SESSION_CAPTURE)
		val state = stateProvider.snapshot()
		return when {
			!state.permissionGranted -> SourceProviderAvailability.PermissionRequired(
				setOf(SourceProviderPermission.RuntimePermission(SourceKind.ACTIVITY)),
			)
			!state.providerAvailable -> SourceProviderAvailability.ProviderUnavailable(
				SourceProviderAvailabilityEvidence.Runtime(
					SourceKind.ACTIVITY,
					setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE),
				),
			)
			else -> SourceProviderAvailability.Available()
		}
	}
}

internal class StepsSessionSourceProviderAvailabilityReader(
	private val stateProvider: ActivityRecognitionSourceStateProvider,
	private val sensorStateProvider: SensorSourceStateProvider,
) : SourceProviderAvailabilityReader {
	override suspend fun read(request: SourceAvailabilityRequest): SourceProviderAvailability {
		require(request.source == SourceKind.STEPS)
		require(request.purpose == TrackingPurpose.SESSION_CAPTURE)
		if (!stateProvider.snapshot().permissionGranted) {
			return SourceProviderAvailability.PermissionRequired(
				setOf(SourceProviderPermission.RuntimePermission(SourceKind.STEPS)),
			)
		}
		return sensorStateProvider.capabilities(SourceKind.STEPS)
			.toSourceProviderAvailability(SourceKind.STEPS)
	}
}

internal class SensorSourceProviderAvailabilityReader(
	private val source: SourceKind,
	private val stateProvider: SensorSourceStateProvider,
) : SourceProviderAvailabilityReader {
	override suspend fun read(request: SourceAvailabilityRequest): SourceProviderAvailability {
		require(request.source == source)
		require(request.purpose == TrackingPurpose.SESSION_CAPTURE)
		return stateProvider.capabilities(source).toSourceProviderAvailability(source)
	}
}

internal class WifiSourceProviderAvailabilityReader(
	private val stateProvider: AndroidConnectivityDeviceStateProvider,
) : SourceProviderAvailabilityReader {
	override suspend fun read(request: SourceAvailabilityRequest): SourceProviderAvailability {
		require(request.source == SourceKind.WIFI)
		val application = WifiPrerequisiteEvaluator.evaluate(
			request.plan as com.adsamcik.tracker.tracker.source.model.WifiPlan,
			stateProvider.wifi(),
		)
		val evidence = SourceProviderAvailabilityEvidence.Runtime(SourceKind.WIFI, application.reasons)
		return when (application.status) {
			SourceApplyStatus.APPLIED -> SourceProviderAvailability.Available()
			SourceApplyStatus.DEGRADED ->
				SourceProviderAvailability.Degraded(application.plan, evidence)
			SourceApplyStatus.BLOCKED -> application.reasons.toBlockedAvailability(
				evidence,
				SourceKind.WIFI,
			)
			SourceApplyStatus.ROLLED_BACK,
			SourceApplyStatus.FAILED,
			-> error("Prerequisite evaluation cannot return ${application.status}")
		}
	}
}

internal class CellSourceProviderAvailabilityReader(
	private val stateProvider: AndroidConnectivityDeviceStateProvider,
) : SourceProviderAvailabilityReader {
	override suspend fun read(request: SourceAvailabilityRequest): SourceProviderAvailability {
		require(request.source == SourceKind.CELL)
		val application = CellPrerequisiteEvaluator.evaluate(
			request.plan as com.adsamcik.tracker.tracker.source.model.CellPlan,
			stateProvider.cell(),
		)
		val evidence = SourceProviderAvailabilityEvidence.Runtime(SourceKind.CELL, application.reasons)
		return when (application.status) {
			SourceApplyStatus.APPLIED -> SourceProviderAvailability.Available()
			SourceApplyStatus.DEGRADED ->
				SourceProviderAvailability.Degraded(application.plan, evidence)
			SourceApplyStatus.BLOCKED -> application.reasons.toBlockedAvailability(
				evidence,
				SourceKind.CELL,
			)
			SourceApplyStatus.ROLLED_BACK,
			SourceApplyStatus.FAILED,
			-> error("Prerequisite evaluation cannot return ${application.status}")
		}
	}
}

internal fun interface SensorSourceStateProvider {
	fun capabilities(source: SourceKind): SourceCapabilities
}

@Singleton
internal class AndroidSensorSourceStateProvider @Inject constructor(
	@ApplicationContext private val context: Context,
) : SensorSourceStateProvider {
	override fun capabilities(source: SourceKind): SourceCapabilities {
		val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
		val sensor = when (source) {
			SourceKind.STEPS -> sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
			SourceKind.PRESSURE -> sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
			else -> error("$source is not a SensorManager source")
		}
		val featureAvailable = source != SourceKind.STEPS ||
			context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)
		return SourceCapabilities(
			available = sensor != null && featureAvailable,
			batchingSupported = sensor?.fifoMaxEventCount?.let { it > 0 } == true,
			flushSupported = sensor?.fifoMaxEventCount?.let { it > 0 } == true,
			maximumBatchSize = sensor?.fifoMaxEventCount,
			minimumDelayMs = sensor?.minDelay
				?.takeIf { it >= 0 }
				?.div(MICROS_PER_MILLISECOND)
				?.toLong(),
		)
	}

	private companion object {
		const val MICROS_PER_MILLISECOND = 1_000
	}
}

internal class LocationSourceProviderAvailabilityReader(
	private val stateProvider: LocationDeviceStateProvider,
	private val prerequisiteEvaluator: LocationPrerequisiteEvaluator,
) : SourceProviderAvailabilityReader {
	override suspend fun read(request: SourceAvailabilityRequest): SourceProviderAvailability {
		require(request.source == SourceKind.LOCATION)
		val plan = request.plan as LocationPlan
		val application = prerequisiteEvaluator.evaluate(
			plan,
			stateProvider.snapshot(),
			request.tier.toLocationStartContext(),
		)
		return application.toSourceProviderAvailability(plan)
	}
}

internal fun containedAvailability(
	reason: TrackingDecisionContainmentReason,
): SourceProviderAvailabilityReader = SourceProviderAvailabilityReader { _ ->
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

internal fun LocationPlanApplication.toSourceProviderAvailability(
	requestedPlan: LocationPlan,
): SourceProviderAvailability {
	val evidence = SourceProviderAvailabilityEvidence.Location(
		requestedBackend = requestedPlan.backend,
		effectiveBackend = plan.backend,
		reasons = reasons,
	)
	return when (status) {
		LocationPlanApplicationStatus.APPLIED -> SourceProviderAvailability.Available()
		LocationPlanApplicationStatus.DEGRADED -> SourceProviderAvailability.Degraded(plan, evidence)
		LocationPlanApplicationStatus.BLOCKED -> reasons.toBlockedAvailability(
			evidence,
			SourceKind.LOCATION,
		)
	}
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

private fun SourceAvailabilityTier.toLocationStartContext(): LocationStartContext = when (this) {
	SourceAvailabilityTier.SESSION_ALREADY_FOREGROUND,
	SourceAvailabilityTier.AMBIENT_REGISTRATION,
	-> LocationStartContext.SESSION_ALREADY_FOREGROUND
	SourceAvailabilityTier.MANUAL_FOREGROUND_START -> LocationStartContext.MANUAL_FOREGROUND_START
	SourceAvailabilityTier.AUTOMATIC_BACKGROUND_START ->
		LocationStartContext.AUTOMATIC_BACKGROUND_START
}

private fun Set<SourceDegradedReason>.toBlockedAvailability(
	evidence: SourceProviderAvailabilityEvidence,
	source: SourceKind,
): SourceProviderAvailability = when {
	SourceDegradedReason.HARDWARE_UNAVAILABLE in this ->
		SourceProviderAvailability.HardwareUnavailable(evidence)
	SourceDegradedReason.PERMISSION_MISSING in this ->
		SourceProviderAvailability.PermissionRequired(
			setOf(SourceProviderPermission.RuntimePermission(source)),
		)
	any(OS_LIMITED_REASONS::contains) -> SourceProviderAvailability.OsLimited(evidence)
	else -> SourceProviderAvailability.ProviderUnavailable(evidence)
}
