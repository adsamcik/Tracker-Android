package com.adsamcik.tracker.tracker.api

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommand

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
		/** Original durable Start, reused after repair rather than issuing a new command. */
		val continuationGeneration: Long,
	) : ManualTrackingStartResult {
		init { require(continuationGeneration > 0L) }
	}

	/** Keeps the exact one-use Start while existing source setup finishes. */
	data class SetupPending(val continuationGeneration: Long) : ManualTrackingStartResult {
		init { require(continuationGeneration > 0L) }
	}

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

	data object SetupPending : ManualTrackingStartReadiness
	data object NoAvailableCaptureSource : ManualTrackingStartReadiness
	data object TrackingUnavailable : ManualTrackingStartReadiness
}

/** Engine boundary that evaluates current policy, rollout, permission, and hardware state. */
fun interface ManualTrackingStartReadinessReader {
	suspend fun read(): ManualTrackingStartReadiness

	/** Only an explicit Start asks existing setup to prepare chosen writers. */
	suspend fun preparePreflight(): ManualTrackingStartPreflight = readPreflight()

	/** Cold existing authority changes, used only while an explicit Start is pending. */
	fun changes(): Flow<Unit> = emptyFlow()

	/** The readiness and chosen sources are read from the same policy snapshot. */
	suspend fun readPreflight(): ManualTrackingStartPreflight =
		ManualTrackingStartPreflight(read(), null)
}

/** Only source participation/consent is pinned; changing collection quality remains allowed. */
data class ManualTrackingStartSelection(
	val captureConsentEpochs: Map<TrackingCaptureSource, Long>,
) {
	init { require(captureConsentEpochs.values.all { it >= 0L }) }

	fun encode(): String = TrackingCaptureSource.entries.mapNotNull { source ->
		captureConsentEpochs[source]?.let { "${source.name}:$it" }
	}.joinToString(",")

	companion object {
		fun decode(value: String): ManualTrackingStartSelection? {
			return try {
				val rows = if (value.isEmpty()) emptyList() else value.split(',')
				if (rows.size > TrackingCaptureSource.entries.size) return null
				val pairs = rows.map { row ->
					val parts = row.split(':')
					require(parts.size == 2)
					TrackingCaptureSource.valueOf(parts[0]) to parts[1].toLong()
				}
				if (pairs.map { it.first }.distinct().size != pairs.size) return null
				ManualTrackingStartSelection(pairs.toMap())
			} catch (_: IllegalArgumentException) { null }
		}
	}
}

data class ManualTrackingStartPreflight(
	val readiness: ManualTrackingStartReadiness,
	val selection: ManualTrackingStartSelection?,
)

data class ManualTrackingStartContinuation(
	val command: TrackingStartCommand,
	val selection: ManualTrackingStartSelection,
)

/** Shared by app screens and external entry points; a repaired Start never reserves a new command. */
internal suspend fun executeManualTrackingStartContinuation(
	continuationGeneration: Long?,
	authority: TrackingLifecycleCommandAuthority,
	readPreflight: suspend () -> ManualTrackingStartPreflight,
	enqueue: suspend (ManualTrackingStartContinuation) -> TrackingStartDispatchResult,
): ManualTrackingStartResult {
	val generationBeforeRead = authority.latestCommandGeneration()
	val original = if (continuationGeneration == null) {
		authority.pendingManualStart?.let { authority.cancelManualStart(it.command.generation) }
		null
	} else {
		authority.pendingManualStart?.takeIf { it.command.generation == continuationGeneration }
			?: return ManualTrackingStartResult.TRACKING_UNAVAILABLE
	}
	val preflight = readPreflight()
	val selection = preflight.selection
	if (selection == null || (original != null && original.selection != selection)) {
		original?.let { authority.cancelManualStart(it.command.generation) }
		return ManualTrackingStartResult.TRACKING_UNAVAILABLE
	}
	when (preflight.readiness) {
		ManualTrackingStartReadiness.NoAvailableCaptureSource -> {
			original?.let { authority.cancelManualStart(it.command.generation) }
			return ManualTrackingStartResult.NO_AVAILABLE_CAPTURE_SOURCE
		}
		ManualTrackingStartReadiness.TrackingUnavailable -> {
			original?.let { authority.cancelManualStart(it.command.generation) }
			return ManualTrackingStartResult.TRACKING_UNAVAILABLE
		}
		else -> Unit
	}
	val request = original ?: authority.reserveManualStartIfGeneration(generationBeforeRead, selection)
		?: return ManualTrackingStartResult.TRACKING_UNAVAILABLE
	return when (val readiness = preflight.readiness) {
		is ManualTrackingStartReadiness.RepairRequired -> {
			if (authority.pendingManualStart != request ||
				authority.latestCommandGeneration() != request.command.generation
			) ManualTrackingStartResult.TRACKING_UNAVAILABLE else
				ManualTrackingStartResult.RepairRequired(readiness.prerequisite, request.command.generation)
		}
		ManualTrackingStartReadiness.SetupPending -> {
			if (authority.pendingManualStart == request && authority.latestCommandGeneration() == request.command.generation)
				ManualTrackingStartResult.SetupPending(request.command.generation)
			else ManualTrackingStartResult.TRACKING_UNAVAILABLE
		}
		is ManualTrackingStartReadiness.Ready -> {
			if (authority.pendingManualStart != request || authority.latestCommandGeneration() != request.command.generation)
				return ManualTrackingStartResult.TRACKING_UNAVAILABLE
			when (val result = enqueue(request)) {
				TrackingStartDispatchResult.Enqueued -> ManualTrackingStartResult.ENQUEUED
				TrackingStartDispatchResult.SetupPending -> if (authority.pendingManualStart == request &&
					authority.latestCommandGeneration() == request.command.generation)
					ManualTrackingStartResult.SetupPending(request.command.generation)
					else ManualTrackingStartResult.TRACKING_UNAVAILABLE
				// Another prepare can see the first one's STARTING row. It must not revoke that same pending delivery.
				TrackingStartDispatchResult.AlreadyActive -> ManualTrackingStartResult.TRACKING_UNAVAILABLE
				is TrackingStartDispatchResult.Retryable, is TrackingStartDispatchResult.Terminal -> {
					authority.cancelManualStart(request.command.generation)
					ManualTrackingStartResult.TRACKING_UNAVAILABLE
				}
			}
		}
		else -> error("Unavailable preflight cannot reserve a Start")
	}
}

/** Shared app-navigation contract for non-Activity surfaces that cannot repair start prerequisites. */
object ManualTrackingStartRepairNavigation {
	private const val EXTRA_NAVIGATE_TO = "navigate_to"
	private const val TARGET_DASHBOARD = "dashboard"
	private const val EXTRA_REEVALUATE_MANUAL_START =
		"com.adsamcik.tracker.extra.REEVALUATE_MANUAL_START"
	private const val EXTRA_MANUAL_START_GENERATION =
		"com.adsamcik.tracker.extra.MANUAL_START_GENERATION"

	fun createDashboardIntent(
		context: Context,
		continuationGeneration: Long? = null,
	): Intent? =
		context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
			putExtra(EXTRA_NAVIGATE_TO, TARGET_DASHBOARD)
			// A navigation hint can never create recording authority.
			removeExtra(EXTRA_REEVALUATE_MANUAL_START)
			removeExtra(EXTRA_MANUAL_START_GENERATION)
			if (continuationGeneration != null && continuationGeneration > 0L) {
				putExtra(EXTRA_MANUAL_START_GENERATION, continuationGeneration)
			}
		}

	/** Removes the handoff extra once; the lifecycle store separately enforces one-use dispatch. */
	fun consumeDashboardStartContinuation(intent: Intent?): Long? {
		val generation = intent?.getLongExtra(EXTRA_MANUAL_START_GENERATION, -1L) ?: -1L
		intent?.removeExtra(EXTRA_MANUAL_START_GENERATION)
		intent?.removeExtra(EXTRA_REEVALUATE_MANUAL_START)
		return generation.takeIf { it > 0L }
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

