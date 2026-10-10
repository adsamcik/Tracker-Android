package com.adsamcik.tracker.tracker.shortcut

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.adsamcik.tracker.tracker.api.ManualTrackingStartRepairNavigation
import com.adsamcik.tracker.tracker.api.ManualTrackingStartResult
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.api.TrackingStopQuiescenceResult
import com.adsamcik.tracker.tracker.api.TrackingTurnOffResult
import com.adsamcik.tracker.tracker.shortcut.Shortcuts.ShortcutAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ShortcutActivity handles shortcut actions with no UI.
 * Follows north star: ComponentActivity pattern for consistency.
 */
@RequiresApi(25)
class ShortcutActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val action = intent.takeIf { it.action == Shortcuts.ACTION }
			?.getIntExtra(Shortcuts.ACTION_STRING, -1)
			?.let { ShortcutAction.entries.getOrNull(it) }
		if (action == null) {
			finish()
			return
		}
		lifecycleScope.launch {
			try {
				onActionReceived(action)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: RuntimeException) {
				openDashboardForRepair()
			}
			finish()
		}
	}

	private suspend fun onActionReceived(action: ShortcutAction) {
		when (action) {
			ShortcutAction.START_COLLECTION -> {
				val result = TrackerServiceApi.requestManualTrackingStart(this)
				if (result != ManualTrackingStartResult.ENQUEUED) {
					openDashboardForRepair(when (result) {
						is ManualTrackingStartResult.RepairRequired -> result.continuationGeneration
						is ManualTrackingStartResult.SetupPending -> result.continuationGeneration
						else -> null
					})
				}
			}
			ShortcutAction.STOP_COLLECTION -> {
				if (withTimeoutOrNull(STOP_ACTION_TIMEOUT_MS) {
					TrackerServiceApi.stopServiceAndAwaitQuiescence(this@ShortcutActivity)
				} !=
					TrackingStopQuiescenceResult.HANDLED
				) openDashboardForRepair()
			}
			ShortcutAction.TURN_OFF_TRACKING -> {
				when (TrackerServiceApi.turnOffTrackingAndAwait(this)) {
					TrackingTurnOffResult.UNAVAILABLE,
					TrackingTurnOffResult.SUPERSEDED_BY_NEW_START ->
						openDashboardForRepair()
					TrackingTurnOffResult.COMPLETE,
					TrackingTurnOffResult.DURABLY_OFF_CLEANUP_PENDING -> Unit
				}
			}
		}
	}

	private fun openDashboardForRepair(continuationGeneration: Long? = null) {
		ManualTrackingStartRepairNavigation.createDashboardIntent(
			this,
			continuationGeneration = continuationGeneration,
		)?.let { startActivity(it) }
	}

	private companion object {
		const val STOP_ACTION_TIMEOUT_MS = 8_000L
	}
}
