package com.adsamcik.tracker.tracker.source.ambient.steps

/** The only system-owned continuity providers admitted for Ambient Steps. */
enum class AmbientStepsProvider {
	HEALTH_CONNECT_MOBILE_STEPS,
	LOCAL_RECORDING_STEPS,
}

/** Permissions have provider-specific meaning and are never treated as interchangeable. */
enum class AmbientStepsPermission {
	HEALTH_CONNECT_READ_STEPS,
	HEALTH_CONNECT_BACKGROUND_READ,
	ACTIVITY_RECOGNITION,
}

/** How soon Tracker may import already-collected provider history while remaining opportunistic. */
enum class AmbientStepsImportAccess {
	/** Import only while Tracker is in a foreground-capable state. */
	FOREGROUND_ONLY,

	/** The platform permits a best-effort background import; no cadence or wake guarantee follows. */
	BACKGROUND_ALLOWED,
}

enum class HealthConnectAmbientStepsAvailability {
	AVAILABLE,
	PLATFORM_TOO_OLD,
	EXTENSION_TOO_OLD,
	SDK_UNAVAILABLE,
	PROVIDER_UPDATE_REQUIRED,
	PROBE_FAILED,
}

enum class LocalRecordingAmbientStepsAvailability {
	AVAILABLE,
	PLAY_SERVICES_MISSING,
	PLAY_SERVICES_UPDATE_REQUIRED,
	PLAY_SERVICES_DISABLED,
	PLAY_SERVICES_INVALID,
	PLAY_SERVICES_UNAVAILABLE,
	PROBE_FAILED,
}

/**
 * A point-in-time platform and permission snapshot. Provider acceptance is a later, durable action;
 * this snapshot alone never authorizes a subscription or creates broker demand.
 */
data class AmbientStepsPlatformSnapshot(
	val healthConnect: HealthConnectAmbientStepsAvailability,
	val healthConnectReadStepsGranted: Boolean = false,
	val healthConnectBackgroundReadAvailable: Boolean = false,
	val healthConnectBackgroundReadGranted: Boolean = false,
	val localRecording: LocalRecordingAmbientStepsAvailability,
	val activityRecognitionRequired: Boolean,
	val activityRecognitionGranted: Boolean,
)

sealed interface AmbientStepsCapability {
	val provider: AmbientStepsProvider?

	/** A single deterministic provider has the grants needed to attempt durable registration. */
	data class ReadyForRegistration(
		override val provider: AmbientStepsProvider,
		val importAccess: AmbientStepsImportAccess,
		val optionalPermissions: Set<AmbientStepsPermission> = emptySet(),
	) : AmbientStepsCapability

	/** The selected provider is capable, but Tracker must obtain these exact grants before use. */
	data class PermissionRequired(
		override val provider: AmbientStepsProvider,
		val requiredPermissions: Set<AmbientStepsPermission>,
		val optionalPermissions: Set<AmbientStepsPermission> = emptySet(),
	) : AmbientStepsCapability

	/** Neither continuity provider is currently usable; direct live-session Steps is not a fallback. */
	data class Unavailable(
		val healthConnect: HealthConnectAmbientStepsAvailability,
		val localRecording: LocalRecordingAmbientStepsAvailability,
	) : AmbientStepsCapability {
		override val provider: AmbientStepsProvider? = null
	}
}

/**
 * Selects exactly one Ambient Steps provider without permission-based silent fallback.
 *
 * Health Connect mobile Steps wins whenever that source is currently capable. A missing Health
 * Connect grant is user action, not permission to switch to Local Recording. Local Recording is
 * considered only when Health Connect mobile Steps is not currently capable on this platform.
 */
object AmbientStepsCapabilitySelector {
	fun select(snapshot: AmbientStepsPlatformSnapshot): AmbientStepsCapability {
		if (snapshot.healthConnect == HealthConnectAmbientStepsAvailability.AVAILABLE) {
			val optionalPermissions = if (
				snapshot.healthConnectBackgroundReadAvailable &&
				!snapshot.healthConnectBackgroundReadGranted
			) {
				setOf(AmbientStepsPermission.HEALTH_CONNECT_BACKGROUND_READ)
			} else {
				emptySet()
			}
			if (!snapshot.healthConnectReadStepsGranted) {
				return AmbientStepsCapability.PermissionRequired(
					provider = AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS,
					requiredPermissions = setOf(AmbientStepsPermission.HEALTH_CONNECT_READ_STEPS),
					optionalPermissions = optionalPermissions,
				)
			}
			return AmbientStepsCapability.ReadyForRegistration(
				provider = AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS,
				importAccess = if (
					snapshot.healthConnectBackgroundReadAvailable &&
					snapshot.healthConnectBackgroundReadGranted
				) {
					AmbientStepsImportAccess.BACKGROUND_ALLOWED
				} else {
					AmbientStepsImportAccess.FOREGROUND_ONLY
				},
				optionalPermissions = optionalPermissions,
			)
		}
		if (snapshot.healthConnect == HealthConnectAmbientStepsAvailability.PROBE_FAILED) {
			return AmbientStepsCapability.Unavailable(
				healthConnect = snapshot.healthConnect,
				localRecording = snapshot.localRecording,
			)
		}

		if (snapshot.localRecording == LocalRecordingAmbientStepsAvailability.AVAILABLE) {
			if (snapshot.activityRecognitionRequired && !snapshot.activityRecognitionGranted) {
				return AmbientStepsCapability.PermissionRequired(
					provider = AmbientStepsProvider.LOCAL_RECORDING_STEPS,
					requiredPermissions = setOf(AmbientStepsPermission.ACTIVITY_RECOGNITION),
				)
			}
			return AmbientStepsCapability.ReadyForRegistration(
				provider = AmbientStepsProvider.LOCAL_RECORDING_STEPS,
				importAccess = AmbientStepsImportAccess.BACKGROUND_ALLOWED,
			)
		}

		return AmbientStepsCapability.Unavailable(
			healthConnect = snapshot.healthConnect,
			localRecording = snapshot.localRecording,
		)
	}
}
