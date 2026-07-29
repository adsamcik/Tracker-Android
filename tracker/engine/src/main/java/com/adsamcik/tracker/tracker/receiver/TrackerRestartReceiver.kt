package com.adsamcik.tracker.tracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.LogicalTrackingLifecycleState
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidate
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import com.adsamcik.tracker.tracker.resilience.TrackingStartupGuard
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TrackerRestartReceiver : BroadcastReceiver() {
	@Inject
	lateinit var startupGuard: TrackingStartupGuard

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != ACTION_RESTART_TRACKER) return
		if (startupGuard.isAutoRecoverySuppressed(context)) {
			Reporter.log("Tracker restart suppressed after package force-stop")
			return
		}

		val descriptor = intent.toDescriptor() ?: return
		if (!descriptor.isRestartEligible) return

		if (!TrackerServiceApi.restartService(context, descriptor)) {
			Reporter.w("TrackerRestartReceiver", "Foreground-service restart was blocked by Android")
		}
	}

	private fun Intent.toDescriptor(): ActiveTrackingSessionDescriptor? {
		val tier = getStringExtra(EXTRA_POLICY_TIER)
			?.let { name -> PolicyTier.entries.firstOrNull { it.name == name } }
			?: return null
		if (tier == PolicyTier.OFF) return null
		val lifecycleStateValue = getStringExtra(EXTRA_LIFECYCLE_STATE)
		val lifecycleState = when {
			// A receiver intent created by a prior app version had no lifecycle state and was active.
			lifecycleStateValue == null -> LogicalTrackingLifecycleState.ACTIVE
			else -> LogicalTrackingLifecycleState.entries.firstOrNull {
				it.name == lifecycleStateValue
			} ?: LogicalTrackingLifecycleState.STOP_CANDIDATE
		}
		val stopCandidate = if (lifecycleState == LogicalTrackingLifecycleState.STOP_CANDIDATE) {
			TrackingStopCandidate(
				reason = getStringExtra(EXTRA_STOP_CANDIDATE_REASON)
					?.let { name ->
						TrackingStopCandidateReason.entries.firstOrNull { it.name == name }
					}
					?: TrackingStopCandidateReason.UNKNOWN,
				requestedAtEpochMs = getLongExtra(
					EXTRA_STOP_CANDIDATE_REQUESTED_AT_EPOCH_MS,
					0L,
				).takeIf { it > 0L },
			)
		} else {
			null
		}
		return ActiveTrackingSessionDescriptor(
			isUserInitiated = getBooleanExtra(EXTRA_USER_INITIATED, false),
			isAmbient = getBooleanExtra(EXTRA_AMBIENT, false),
			policyTier = tier,
			logicalTrackingId = getStringExtra(EXTRA_LOGICAL_TRACKING_ID)
				?.takeIf { it.isNotBlank() }
				?: java.util.UUID.randomUUID().toString(),
			lifecycleState = lifecycleState,
			lifecycleRevision = getLongExtra(EXTRA_LIFECYCLE_REVISION, 0L).coerceAtLeast(0L),
			lifecycleChangedAtEpochMs = getLongExtra(
				EXTRA_LIFECYCLE_CHANGED_AT_EPOCH_MS,
				0L,
			).takeIf { it > 0L },
			stopCandidate = stopCandidate,
		)
	}

	companion object {
		const val ACTION_RESTART_TRACKER =
			"com.adsamcik.tracker.tracker.action.RESTART_TRACKER"
		const val EXTRA_USER_INITIATED = "restartUserInitiated"
		const val EXTRA_AMBIENT = "restartAmbient"
		const val EXTRA_POLICY_TIER = "restartPolicyTier"
		const val EXTRA_LOGICAL_TRACKING_ID = "restartLogicalTrackingId"
		const val EXTRA_LIFECYCLE_STATE = "restartLogicalLifecycleState"
		const val EXTRA_LIFECYCLE_REVISION = "restartLogicalLifecycleRevision"
		const val EXTRA_LIFECYCLE_CHANGED_AT_EPOCH_MS = "restartLogicalLifecycleChangedAtEpochMs"
		const val EXTRA_STOP_CANDIDATE_REASON = "restartStopCandidateReason"
		const val EXTRA_STOP_CANDIDATE_REQUESTED_AT_EPOCH_MS =
			"restartStopCandidateRequestedAtEpochMs"

		fun intent(
			context: Context,
			descriptor: ActiveTrackingSessionDescriptor,
		): Intent = Intent(context, TrackerRestartReceiver::class.java).apply {
			action = ACTION_RESTART_TRACKER
			putExtra(EXTRA_USER_INITIATED, descriptor.isUserInitiated)
			putExtra(EXTRA_AMBIENT, descriptor.isAmbient)
			putExtra(EXTRA_POLICY_TIER, descriptor.policyTier.name)
			putExtra(EXTRA_LOGICAL_TRACKING_ID, descriptor.logicalTrackingId)
			putExtra(EXTRA_LIFECYCLE_STATE, descriptor.lifecycleState.name)
			putExtra(EXTRA_LIFECYCLE_REVISION, descriptor.lifecycleRevision)
			descriptor.lifecycleChangedAtEpochMs?.let {
				putExtra(EXTRA_LIFECYCLE_CHANGED_AT_EPOCH_MS, it)
			}
			descriptor.stopCandidate?.let { candidate ->
				putExtra(EXTRA_STOP_CANDIDATE_REASON, candidate.reason.name)
				candidate.requestedAtEpochMs?.let {
					putExtra(EXTRA_STOP_CANDIDATE_REQUESTED_AT_EPOCH_MS, it)
				}
			}
		}
	}
}
