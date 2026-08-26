package com.adsamcik.tracker.tracker.api

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** The finite set of user-repairable prerequisites understood by manual tracking start. */
enum class ManualTrackingStartPrerequisite {
	PRECISE_LOCATION_PERMISSION,
	ACTIVITY_RECOGNITION_PERMISSION,
	READ_PHONE_STATE_PERMISSION,
	LOCATION_SERVICES,
}

/** The one typed outcome returned to every user-initiated tracking entry point. */
sealed interface ManualTrackingStartResult {
	/** Durable preparation completed and Android accepted the prepared service start. */
	data object ENQUEUED : ManualTrackingStartResult

	/** A foreground Activity may repair this known prerequisite and retry the same operation. */
	data class RepairRequired(
		val prerequisite: ManualTrackingStartPrerequisite,
	) : ManualTrackingStartResult

	/** Settings are enabled, but no enabled and reachable source can run on this device right now. */
	data object NO_AVAILABLE_CAPTURE_SOURCE : ManualTrackingStartResult

	/** Startup, rollout authority, policy, or the durable Android enqueue boundary is unavailable. */
	data object TRACKING_UNAVAILABLE : ManualTrackingStartResult
}

/** Read-only counterpart used to render a truthful manual-start action before the user taps it. */
sealed interface ManualTrackingStartReadiness {
	data class Ready(val rolloutRevision: Long) : ManualTrackingStartReadiness {
		init {
			require(rolloutRevision >= 0L)
		}
	}

	data class RepairRequired(
		val prerequisite: ManualTrackingStartPrerequisite,
	) : ManualTrackingStartReadiness

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
	private const val EXTRA_REEVALUATE_MANUAL_START =
		"com.adsamcik.tracker.extra.REEVALUATE_MANUAL_START"

	fun createDashboardIntent(context: Context): Intent? =
		context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
			putExtra(EXTRA_NAVIGATE_TO, TARGET_DASHBOARD)
			putExtra(EXTRA_REEVALUATE_MANUAL_START, true)
		}

	/** Consumes the one-shot request while leaving the stable Dashboard destination untouched. */
	fun consumeDashboardReevaluationRequest(intent: Intent?): Boolean {
		if (intent?.getBooleanExtra(EXTRA_REEVALUATE_MANUAL_START, false) != true) return false
		intent.removeExtra(EXTRA_REEVALUATE_MANUAL_START)
		return true
	}

	/** Opens the platform repair surface without assuming the caller is an Activity or OEM support. */
	fun openLocationServicesSettings(context: Context): Boolean {
		val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
			if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}
		return try {
			context.startActivity(intent)
			true
		} catch (_: ActivityNotFoundException) {
			false
		} catch (_: SecurityException) {
			false
		}
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
	supportedSources: Set<TrackingCaptureSource>,
	availableSources: Set<TrackingCaptureSource>,
	sourcesMissingPreciseLocationPermission: Set<TrackingCaptureSource>,
	sourcesMissingActivityRecognitionPermission: Set<TrackingCaptureSource>,
	sourcesMissingReadPhoneStatePermission: Set<TrackingCaptureSource>,
	sourcesBlockedByLocationServices: Set<TrackingCaptureSource>,
): ManualTrackingStartReadiness {
	require(rolloutRevision >= 0L)
	val enabledAndReachable = enabledSources intersect reachableSources
	if (enabledAndReachable.any(availableSources::contains)) {
		return ManualTrackingStartReadiness.Ready(rolloutRevision)
	}
	if (enabledSources.isNotEmpty() && enabledAndReachable.isEmpty()) {
		return ManualTrackingStartReadiness.TrackingUnavailable
	}

	val repairableCandidates = (enabledAndReachable intersect supportedSources)
		.map { source -> source to missingPrerequisites(
			source = source,
			sourcesMissingPreciseLocationPermission = sourcesMissingPreciseLocationPermission,
			sourcesMissingActivityRecognitionPermission =
				sourcesMissingActivityRecognitionPermission,
			sourcesMissingReadPhoneStatePermission = sourcesMissingReadPhoneStatePermission,
			sourcesBlockedByLocationServices = sourcesBlockedByLocationServices,
		) }
		.filter { (_, prerequisites) -> prerequisites.isNotEmpty() }
	val selectedRepair = repairableCandidates.minWithOrNull(
		compareBy<Pair<TrackingCaptureSource, List<ManualTrackingStartPrerequisite>>>(
			{ (_, prerequisites) -> prerequisites.size },
			{ (source, _) -> source.ordinal },
		),
	)?.second?.firstOrNull()
	return selectedRepair?.let(ManualTrackingStartReadiness::RepairRequired)
		?: ManualTrackingStartReadiness.NoAvailableCaptureSource
}

private fun missingPrerequisites(
	source: TrackingCaptureSource,
	sourcesMissingPreciseLocationPermission: Set<TrackingCaptureSource>,
	sourcesMissingActivityRecognitionPermission: Set<TrackingCaptureSource>,
	sourcesMissingReadPhoneStatePermission: Set<TrackingCaptureSource>,
	sourcesBlockedByLocationServices: Set<TrackingCaptureSource>,
): List<ManualTrackingStartPrerequisite> = buildList {
	if (source in sourcesMissingPreciseLocationPermission) {
		add(ManualTrackingStartPrerequisite.PRECISE_LOCATION_PERMISSION)
	}
	if (source in sourcesMissingActivityRecognitionPermission) {
		add(ManualTrackingStartPrerequisite.ACTIVITY_RECOGNITION_PERMISSION)
	}
	if (source in sourcesMissingReadPhoneStatePermission) {
		add(ManualTrackingStartPrerequisite.READ_PHONE_STATE_PERMISSION)
	}
	if (source in sourcesBlockedByLocationServices) {
		add(ManualTrackingStartPrerequisite.LOCATION_SERVICES)
	}
}

/** Maps readiness plus the authoritative enqueue acknowledgement to the public result. */
internal suspend fun executeManualTrackingStart(
	readReadiness: suspend () -> ManualTrackingStartReadiness,
	enqueuePreparedStart: suspend () -> Boolean,
): ManualTrackingStartResult = when (val readiness = readReadiness()) {
	is ManualTrackingStartReadiness.Ready -> if (enqueuePreparedStart()) {
		ManualTrackingStartResult.ENQUEUED
	} else {
		ManualTrackingStartResult.TRACKING_UNAVAILABLE
	}
	is ManualTrackingStartReadiness.RepairRequired ->
		ManualTrackingStartResult.RepairRequired(readiness.prerequisite)
	ManualTrackingStartReadiness.NoAvailableCaptureSource ->
		ManualTrackingStartResult.NO_AVAILABLE_CAPTURE_SOURCE
	ManualTrackingStartReadiness.TrackingUnavailable ->
		ManualTrackingStartResult.TRACKING_UNAVAILABLE
}
