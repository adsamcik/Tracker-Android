package com.adsamcik.tracker.tracker.api

import android.content.Context
import android.content.Intent

/** The one typed outcome returned to every user-initiated tracking entry point. */
enum class ManualTrackingStartResult {
	/** Durable preparation completed and Android accepted the prepared service start. */
	ENQUEUED,
	/** A foreground Activity may request precise Location and retry the same operation. */
	PRECISE_LOCATION_PERMISSION_REQUIRED,
	/** Settings are enabled, but no enabled and reachable source can run on this device right now. */
	NO_AVAILABLE_CAPTURE_SOURCE,
	/** Startup, rollout authority, policy, or the durable Android enqueue boundary is unavailable. */
	TRACKING_UNAVAILABLE,
}

/** Read-only counterpart used to render a truthful manual-start action before the user taps it. */
sealed interface ManualTrackingStartReadiness {
	data class Ready(val rolloutRevision: Long) : ManualTrackingStartReadiness {
		init {
			require(rolloutRevision >= 0L)
		}
	}

	data object PreciseLocationPermissionRequired : ManualTrackingStartReadiness
	data object NoAvailableCaptureSource : ManualTrackingStartReadiness
	data object TrackingUnavailable : ManualTrackingStartReadiness
}

/** Engine boundary that evaluates current policy, rollout, permission, and hardware state. */
fun interface ManualTrackingStartReadinessReader {
	suspend fun read(): ManualTrackingStartReadiness
}

/** Shared app-navigation contract for non-Activity surfaces that cannot repair start prerequisites. */
object ManualTrackingStartRepairNavigation {
	private const val EXTRA_NAVIGATE_TO = "navigate_to"
	private const val TARGET_DASHBOARD = "dashboard"

	fun createDashboardIntent(context: Context): Intent? =
		context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
			putExtra(EXTRA_NAVIGATE_TO, TARGET_DASHBOARD)
		}
}

/**
 * Resolves manual-start readiness without making one source a hidden prerequisite for another.
 * Any enabled, rollout-reachable, currently available source is sufficient.
 */
fun resolveManualTrackingStartReadiness(
	rolloutRevision: Long,
	enabledSources: Set<TrackingCaptureSource>,
	reachableSources: Set<TrackingCaptureSource>,
	availableSources: Set<TrackingCaptureSource>,
	preciseLocationPermissionWouldEnable: Set<TrackingCaptureSource>,
): ManualTrackingStartReadiness {
	require(rolloutRevision >= 0L)
	val enabledAndReachable = enabledSources intersect reachableSources
	if (enabledAndReachable.any(availableSources::contains)) {
		return ManualTrackingStartReadiness.Ready(rolloutRevision)
	}
	if (enabledAndReachable.any(preciseLocationPermissionWouldEnable::contains)) {
		return ManualTrackingStartReadiness.PreciseLocationPermissionRequired
	}
	return if (enabledSources.isNotEmpty() && enabledAndReachable.isEmpty()) {
		ManualTrackingStartReadiness.TrackingUnavailable
	} else {
		ManualTrackingStartReadiness.NoAvailableCaptureSource
	}
}

/** Maps readiness plus the authoritative enqueue acknowledgement to the public result. */
internal suspend fun executeManualTrackingStart(
	readReadiness: suspend () -> ManualTrackingStartReadiness,
	enqueuePreparedStart: suspend () -> Boolean,
): ManualTrackingStartResult = when (readReadiness()) {
	is ManualTrackingStartReadiness.Ready -> if (enqueuePreparedStart()) {
		ManualTrackingStartResult.ENQUEUED
	} else {
		ManualTrackingStartResult.TRACKING_UNAVAILABLE
	}
	ManualTrackingStartReadiness.PreciseLocationPermissionRequired ->
		ManualTrackingStartResult.PRECISE_LOCATION_PERMISSION_REQUIRED
	ManualTrackingStartReadiness.NoAvailableCaptureSource ->
		ManualTrackingStartResult.NO_AVAILABLE_CAPTURE_SOURCE
	ManualTrackingStartReadiness.TrackingUnavailable ->
		ManualTrackingStartResult.TRACKING_UNAVAILABLE
}
