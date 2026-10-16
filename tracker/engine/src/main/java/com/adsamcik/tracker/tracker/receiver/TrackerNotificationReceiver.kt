package com.adsamcik.tracker.tracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.controller.LockManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Receives notification actions
 */
@AndroidEntryPoint
internal class TrackerNotificationReceiver : BroadcastReceiver() {
	
	@Inject
	lateinit var lockManager: LockManager

	@Inject
	@ApplicationScope
	lateinit var applicationScope: CoroutineScope
	
	override fun onReceive(context: Context, intent: Intent) {
		when (intent.getIntExtra(ACTION_STRING, -1)) {
			STOP_TRACKING_ACTION -> {
				launchBoundedControlAction(STOP_RECEIVER_TIMEOUT_MS) {
					TrackerServiceApi.stopServiceAndAwaitQuiescence(context.applicationContext)
				}
			}
			TURN_OFF_TRACKING_ACTION -> {
				launchBoundedControlAction(TURN_OFF_RECEIVER_TIMEOUT_MS) {
					TrackerServiceApi.turnOffTrackingAndAwait(context.applicationContext)
				}
			}
			LOCK_RECHARGE_ACTION -> {
				lockManager.lockUntilRecharge(context)
			}
			LOCK_TIME_ACTION -> {
				val minutes = intent.getIntExtra(STOP_MINUTES_EXTRA, -1)
				if (minutes > 0) {
					lockManager.lockTimeLock(context, Time.MINUTE_IN_MILLISECONDS * minutes)
				}
			}
			else -> Unit
		}
	}

	/** Finish the broadcast on time even if provider retirement ignores caller cancellation. */
	private fun launchBoundedControlAction(timeoutMs: Long, action: suspend () -> Unit) {
		val pendingResult = goAsync()
		val finished = AtomicBoolean(false)
		val timeout = applicationScope.launch {
			delay(timeoutMs)
			if (finished.compareAndSet(false, true)) {
				pendingResult.finish()
			}
		}
		applicationScope.launch {
			try {
				action()
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				// The original durable command remains available for recovery or user retry.
			} finally {
				timeout.cancel()
				if (finished.compareAndSet(false, true)) {
					pendingResult.finish()
				}
			}
		}
	}

	companion object {
		internal const val ACTION_STRING: String = "action"
		internal const val STOP_MINUTES_EXTRA: String = "stopForMinutes"

		internal const val STOP_TRACKING_ACTION: Int = 1
		internal const val LOCK_RECHARGE_ACTION: Int = 2
		internal const val LOCK_TIME_ACTION: Int = 3
		internal const val TURN_OFF_TRACKING_ACTION: Int = 4
		private const val STOP_RECEIVER_TIMEOUT_MS = 8_000L
		private const val TURN_OFF_RECEIVER_TIMEOUT_MS = 8_000L
	}
}
