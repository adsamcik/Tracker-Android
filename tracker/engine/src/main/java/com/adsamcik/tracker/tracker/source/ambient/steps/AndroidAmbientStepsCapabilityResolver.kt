package com.adsamcik.tracker.tracker.source.ambient.steps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.fitness.LocalRecordingClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Reads current platform support and permission state without registering either provider. */
@Singleton
class AndroidAmbientStepsCapabilityResolver @Inject constructor(
	@ApplicationContext private val context: Context,
) {
	suspend fun resolve(): AmbientStepsCapability = AmbientStepsCapabilitySelector.select(snapshot())

	internal suspend fun snapshot(): AmbientStepsPlatformSnapshot {
		val healthConnect = healthConnectAvailability()
		val healthState = if (healthConnect == HealthConnectAmbientStepsAvailability.AVAILABLE) {
			readHealthConnectState()
		} else {
			HealthConnectPermissionState()
		}
		return AmbientStepsPlatformSnapshot(
			healthConnect = healthState.failure ?: healthConnect,
			healthConnectReadStepsGranted = healthState.readStepsGranted,
			healthConnectBackgroundReadAvailable = healthState.backgroundReadAvailable,
			healthConnectBackgroundReadGranted = healthState.backgroundReadGranted,
			localRecording = localRecordingAvailability(),
			activityRecognitionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
			activityRecognitionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
				ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
				PackageManager.PERMISSION_GRANTED,
		)
	}

	private fun healthConnectAvailability(): HealthConnectAmbientStepsAvailability {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			return HealthConnectAmbientStepsAvailability.PLATFORM_TOO_OLD
		}
		if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) <
			MOBILE_STEPS_MIN_EXTENSION
		) {
			return HealthConnectAmbientStepsAvailability.EXTENSION_TOO_OLD
		}
		return try {
			when (HealthConnectClient.getSdkStatus(context)) {
				HealthConnectClient.SDK_AVAILABLE -> HealthConnectAmbientStepsAvailability.AVAILABLE
				HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
					HealthConnectAmbientStepsAvailability.PROVIDER_UPDATE_REQUIRED
				else -> HealthConnectAmbientStepsAvailability.SDK_UNAVAILABLE
			}
		} catch (_: RuntimeException) {
			HealthConnectAmbientStepsAvailability.PROBE_FAILED
		} catch (_: LinkageError) {
			HealthConnectAmbientStepsAvailability.PROBE_FAILED
		}
	}

	private suspend fun readHealthConnectState(): HealthConnectPermissionState = try {
		val client = HealthConnectClient.getOrCreate(context)
		val granted = client.permissionController.getGrantedPermissions()
		val backgroundReadAvailable = client.features.getFeatureStatus(
			HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
		) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
		HealthConnectPermissionState(
			readStepsGranted = HealthPermission.getReadPermission(StepsRecord::class) in granted,
			backgroundReadAvailable = backgroundReadAvailable,
			backgroundReadGranted = backgroundReadAvailable &&
				HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted,
		)
	} catch (cancellation: CancellationException) {
		throw cancellation
	} catch (_: RuntimeException) {
		HealthConnectPermissionState(failure = HealthConnectAmbientStepsAvailability.PROBE_FAILED)
	} catch (_: LinkageError) {
		HealthConnectPermissionState(failure = HealthConnectAmbientStepsAvailability.PROBE_FAILED)
	}

	private fun localRecordingAvailability(): LocalRecordingAmbientStepsAvailability = try {
		when (
			GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(
				context,
				LocalRecordingClient.LOCAL_RECORDING_CLIENT_STEPS_MIN_VERSION_CODE,
			)
		) {
			ConnectionResult.SUCCESS -> LocalRecordingAmbientStepsAvailability.AVAILABLE
			ConnectionResult.SERVICE_MISSING ->
				LocalRecordingAmbientStepsAvailability.PLAY_SERVICES_MISSING
			ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED ->
				LocalRecordingAmbientStepsAvailability.PLAY_SERVICES_UPDATE_REQUIRED
			ConnectionResult.SERVICE_DISABLED ->
				LocalRecordingAmbientStepsAvailability.PLAY_SERVICES_DISABLED
			ConnectionResult.SERVICE_INVALID ->
				LocalRecordingAmbientStepsAvailability.PLAY_SERVICES_INVALID
			else -> LocalRecordingAmbientStepsAvailability.PLAY_SERVICES_UNAVAILABLE
		}
	} catch (_: RuntimeException) {
		LocalRecordingAmbientStepsAvailability.PROBE_FAILED
	} catch (_: LinkageError) {
		LocalRecordingAmbientStepsAvailability.PROBE_FAILED
	}

	private data class HealthConnectPermissionState(
		val readStepsGranted: Boolean = false,
		val backgroundReadAvailable: Boolean = false,
		val backgroundReadGranted: Boolean = false,
		val failure: HealthConnectAmbientStepsAvailability? = null,
	)

	private companion object {
		const val MOBILE_STEPS_MIN_EXTENSION = 20
	}
}
