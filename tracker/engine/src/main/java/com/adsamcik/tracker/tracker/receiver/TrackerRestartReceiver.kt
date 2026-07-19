package com.adsamcik.tracker.tracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
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
		if (!descriptor.isUserInitiated) return

		if (!TrackerServiceApi.restartService(context, descriptor)) {
			Reporter.w("TrackerRestartReceiver", "Foreground-service restart was blocked by Android")
		}
	}

	private fun Intent.toDescriptor(): ActiveTrackingSessionDescriptor? {
		val tier = getStringExtra(EXTRA_POLICY_TIER)
			?.let { name -> PolicyTier.entries.firstOrNull { it.name == name } }
			?: return null
		if (tier == PolicyTier.OFF) return null
		return ActiveTrackingSessionDescriptor(
			isUserInitiated = getBooleanExtra(EXTRA_USER_INITIATED, false),
			isAmbient = getBooleanExtra(EXTRA_AMBIENT, false),
			policyTier = tier,
		)
	}

	companion object {
		const val ACTION_RESTART_TRACKER =
			"com.adsamcik.tracker.tracker.action.RESTART_TRACKER"
		const val EXTRA_USER_INITIATED = "restartUserInitiated"
		const val EXTRA_AMBIENT = "restartAmbient"
		const val EXTRA_POLICY_TIER = "restartPolicyTier"

		fun intent(
			context: Context,
			descriptor: ActiveTrackingSessionDescriptor,
		): Intent = Intent(context, TrackerRestartReceiver::class.java).apply {
			action = ACTION_RESTART_TRACKER
			putExtra(EXTRA_USER_INITIATED, descriptor.isUserInitiated)
			putExtra(EXTRA_AMBIENT, descriptor.isAmbient)
			putExtra(EXTRA_POLICY_TIER, descriptor.policyTier.name)
		}
	}
}
