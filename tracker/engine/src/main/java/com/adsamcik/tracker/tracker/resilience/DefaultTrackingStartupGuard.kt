package com.adsamcik.tracker.tracker.resilience

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

@Singleton
class DefaultTrackingStartupGuard @Inject constructor() : TrackingStartupGuard {
	@Volatile
	private var cachedResult: Boolean? = null
	private val processGate = TrackingAutoRecoveryProcessGate()

	override fun wasForceStopped(context: Context): Boolean {
		cachedResult?.let { return it }
		return synchronized(this) {
			cachedResult ?: detectForceStop(context).also { cachedResult = it }
		}
	}

	override fun suppressAutoRecoveryForCurrentProcess() {
		processGate.suppressAfterConfirmedForceStop()
	}

	override fun recordExplicitForegroundLaunch(context: Context): Boolean =
		processGate.recordExplicitForegroundLaunch(wasForceStopped(context))

	override suspend fun awaitAutoRecoveryAuthorizationAfterStartupReady(
		context: Context,
	): TrackingAutoRecoveryAuthorization =
		processGate.awaitAuthorizationAfterStartupReady(wasForceStopped(context))

	override fun releaseAutoRecoveryForReadyGeneration(context: Context): Boolean =
		processGate.releaseForReadyGeneration(wasForceStopped(context))

	override fun isAutoRecoverySuppressed(context: Context): Boolean =
		processGate.isSuppressed(wasForceStopped(context))

	private fun detectForceStop(context: Context): Boolean {
		// There is no exact force-stop signal on API 26-34. Historical exit reasons on API
		// 30-34 remain ambiguous and are handled by ordinary previous-exit reconciliation.
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false
		return try {
			val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
			activityManager.getHistoricalProcessStartReasons(1)
				.firstOrNull()
				?.wasForceStopped() == true
		} catch (exception: RuntimeException) {
			false
		}
	}
}

/** Foreground intent and authorization precede release inside a verified startup Ready generation. */
internal class TrackingAutoRecoveryProcessGate {
	private val state = MutableStateFlow(ProcessAutoRecoveryState.UNRESOLVED)
	private val monitor = Any()

	fun suppressAfterConfirmedForceStop() {
		synchronized(monitor) {
			if (state.value == ProcessAutoRecoveryState.UNRESOLVED) {
				state.value = ProcessAutoRecoveryState.FORCE_STOP_SUPPRESSED
			}
		}
	}

	fun recordExplicitForegroundLaunch(confirmedForceStop: Boolean): Boolean {
		if (!confirmedForceStop) return false
		return synchronized(monitor) {
			when (state.value) {
				ProcessAutoRecoveryState.UNRESOLVED,
				ProcessAutoRecoveryState.FORCE_STOP_SUPPRESSED,
				-> {
					state.value = ProcessAutoRecoveryState.EXPLICIT_FOREGROUND_PENDING
					true
				}
				ProcessAutoRecoveryState.EXPLICIT_FOREGROUND_PENDING,
				ProcessAutoRecoveryState.EXPLICIT_FOREGROUND_AUTHORIZED,
				ProcessAutoRecoveryState.EXPLICIT_FOREGROUND_RELEASED,
				-> false
			}
		}
	}

	fun isSuppressed(confirmedForceStop: Boolean): Boolean = when (state.value) {
		ProcessAutoRecoveryState.EXPLICIT_FOREGROUND_RELEASED -> false
		ProcessAutoRecoveryState.FORCE_STOP_SUPPRESSED,
		ProcessAutoRecoveryState.EXPLICIT_FOREGROUND_PENDING,
		ProcessAutoRecoveryState.EXPLICIT_FOREGROUND_AUTHORIZED,
		-> true
		ProcessAutoRecoveryState.UNRESOLVED -> confirmedForceStop
	}

	suspend fun awaitAuthorizationAfterStartupReady(
		confirmedForceStop: Boolean,
	): TrackingAutoRecoveryAuthorization {
		if (!confirmedForceStop) return TrackingAutoRecoveryAuthorization.ORDINARY_START
		while (true) {
			when (state.value) {
				ProcessAutoRecoveryState.EXPLICIT_FOREGROUND_RELEASED ->
					return TrackingAutoRecoveryAuthorization.EXPLICIT_FOREGROUND_AFTER_FORCE_STOP
				ProcessAutoRecoveryState.EXPLICIT_FOREGROUND_AUTHORIZED ->
					return TrackingAutoRecoveryAuthorization.EXPLICIT_FOREGROUND_AFTER_FORCE_STOP
				ProcessAutoRecoveryState.EXPLICIT_FOREGROUND_PENDING -> {
					val authorized = synchronized(monitor) {
						if (state.value == ProcessAutoRecoveryState.EXPLICIT_FOREGROUND_PENDING) {
							state.value = ProcessAutoRecoveryState.EXPLICIT_FOREGROUND_AUTHORIZED
							true
						} else {
							false
						}
					}
					if (authorized) {
						return TrackingAutoRecoveryAuthorization.EXPLICIT_FOREGROUND_AFTER_FORCE_STOP
					}
				}
				ProcessAutoRecoveryState.UNRESOLVED,
				ProcessAutoRecoveryState.FORCE_STOP_SUPPRESSED,
				-> state.first { next ->
					next == ProcessAutoRecoveryState.EXPLICIT_FOREGROUND_PENDING ||
						next == ProcessAutoRecoveryState.EXPLICIT_FOREGROUND_AUTHORIZED ||
						next == ProcessAutoRecoveryState.EXPLICIT_FOREGROUND_RELEASED
				}
			}
		}
	}

	fun releaseForReadyGeneration(confirmedForceStop: Boolean): Boolean {
		if (!confirmedForceStop) return false
		return synchronized(monitor) {
			when (state.value) {
				ProcessAutoRecoveryState.EXPLICIT_FOREGROUND_AUTHORIZED -> {
					state.value = ProcessAutoRecoveryState.EXPLICIT_FOREGROUND_RELEASED
					true
				}
				ProcessAutoRecoveryState.EXPLICIT_FOREGROUND_RELEASED -> true
				ProcessAutoRecoveryState.UNRESOLVED,
				ProcessAutoRecoveryState.FORCE_STOP_SUPPRESSED,
				ProcessAutoRecoveryState.EXPLICIT_FOREGROUND_PENDING,
				-> false
			}
		}
	}
}

private enum class ProcessAutoRecoveryState {
	UNRESOLVED,
	FORCE_STOP_SUPPRESSED,
	EXPLICIT_FOREGROUND_PENDING,
	EXPLICIT_FOREGROUND_AUTHORIZED,
	EXPLICIT_FOREGROUND_RELEASED,
}
