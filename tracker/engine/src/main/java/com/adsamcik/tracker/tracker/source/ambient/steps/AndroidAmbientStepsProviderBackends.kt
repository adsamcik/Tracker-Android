package com.adsamcik.tracker.tracker.source.ambient.steps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.fitness.FitnessLocal
import com.google.android.gms.fitness.data.LocalDataType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

/**
 * Health Connect starts on-device step capture from the granted READ_STEPS permission. There is no
 * Tracker-owned subscription to install or remove, so activation only re-checks the selected
 * capability immediately before the durable generation is accepted.
 */
@Singleton
internal class HealthConnectAmbientStepsProviderBackend internal constructor(
	private val resolveCapability: suspend () -> AmbientStepsCapability,
) : AmbientStepsProviderBackend {
	@Inject
	constructor(
		capabilityResolver: AndroidAmbientStepsCapabilityResolver,
	) : this(capabilityResolver::resolve)

	override val provider: AmbientStepsProvider =
		AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS

	override suspend fun ensureActive() {
		val capability = resolveCapability()
		check(
			capability is AmbientStepsCapability.ReadyForRegistration &&
				capability.provider == provider,
		) { "Health Connect mobile Steps is no longer authorized" }
	}

	override suspend fun remove() = Unit
}

/**
 * Accountless Google Play services Local Recording subscription for mobile step deltas.
 *
 * Both operations are provider-defined idempotent calls. Subscription lifetime is global to this
 * app and data type, which is why generation handoff never removes a same-provider predecessor.
 */
@Singleton
internal class LocalRecordingAmbientStepsProviderBackend internal constructor(
	private val subscribe: suspend () -> Unit,
	private val unsubscribe: suspend () -> Unit,
) : AmbientStepsProviderBackend {
	@Inject
	constructor(
		@ApplicationContext context: Context,
	) : this(
		subscribe = {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
				ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) !=
				PackageManager.PERMISSION_GRANTED
			) {
				throw SecurityException("Local Recording Steps permission is unavailable")
			}
			try {
				FitnessLocal.getLocalRecordingClient(context)
					.subscribe(LocalDataType.TYPE_STEP_COUNT_DELTA)
					.await()
			} catch (denied: SecurityException) {
				throw SecurityException("Local Recording Steps permission was revoked", denied)
			}
		},
		unsubscribe = {
			FitnessLocal.getLocalRecordingClient(context)
				.unsubscribe(LocalDataType.TYPE_STEP_COUNT_DELTA)
				.await()
		},
	)

	override val provider: AmbientStepsProvider =
		AmbientStepsProvider.LOCAL_RECORDING_STEPS

	override suspend fun ensureActive() = subscribe()

	override suspend fun remove() = unsubscribe()
}
