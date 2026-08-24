package com.adsamcik.tracker.tracker.source.projection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity
import com.adsamcik.tracker.shared.base.database.reconcileActivityAutomationEpochInTransaction
import com.adsamcik.tracker.shared.base.database.rotateActivityAutomationEpochInTransaction
import com.adsamcik.tracker.shared.base.extension.powerManager
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.tracker.controller.LockManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Durable Activity automatic-start generation and its runtime suppression boundary monitor. */
@Singleton
class ActivityAutomationEpochAuthority @Inject constructor(
	private val database: AppDatabase,
	@ApplicationContext private val context: Context,
	private val lockManager: LockManager,
	private val clock: Clock,
	private val bootClockDomainProvider: BootClockDomainProvider,
) {
	private val monitoringStarted = AtomicBoolean(false)

	/** Stamps callbacks only after lock/power state has been reconciled into the durable epoch. */
	suspend fun epochForCallbackAdmission(): ActivityAutomationEpochEntity =
		reconcileRuntimeSuppression(ACTIVITY_CALLBACK_BOUNDARY)

	/** Rechecks current Android suppression at each delayed outbox/service validation boundary. */
	suspend fun currentForValidation(): ActivityAutomationEpochEntity =
		reconcileRuntimeSuppression(RUNTIME_VALIDATION_BOUNDARY)

	suspend fun rotate(reason: String, updatedAtMs: Long = clock.currentTimeMillis()): Long =
		database.withTransaction {
			database.rotateActivityAutomationEpochInTransaction(
				reason = reason,
				updatedAtMs = updatedAtMs,
				bootClockDomainId = bootClockDomainProvider.current(),
				effectiveElapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
			).epoch
		}

	/**
	 * Observes both sides of lock and power-saver suppression. Boundary validation still reconciles
	 * synchronously, so a callback racing this process-local monitor cannot inherit the wrong epoch.
	 */
	fun startRuntimeBoundaryMonitoring(scope: CoroutineScope) {
		if (!monitoringStarted.compareAndSet(false, true)) return
		scope.launch {
			lockManager.isLockedFlow.collect {
				reconcileRuntimeSuppression(LOCK_SUPPRESSION_BOUNDARY)
			}
		}
		val receiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context?, intent: Intent?) {
				if (intent?.action != PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) return
				scope.launch { reconcileRuntimeSuppression(POWER_SAVER_SUPPRESSION_BOUNDARY) }
			}
		}
		ContextCompat.registerReceiver(
			context,
			receiver,
			IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)
		scope.launch { reconcileRuntimeSuppression(PROCESS_RUNTIME_RECONCILIATION) }
	}

	private suspend fun reconcileRuntimeSuppression(
		reason: String,
	): ActivityAutomationEpochEntity = database.withTransaction {
		database.reconcileActivityAutomationEpochInTransaction(
			lockSuppressed = lockManager.isLocked,
			powerSaverSuppressed = context.powerManager.isPowerSaveMode,
			bootClockDomainId = bootClockDomainProvider.current(),
			effectiveElapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
			reason = reason,
			updatedAtMs = clock.currentTimeMillis(),
		)
	}

	companion object {
		const val ACTIVITY_CALLBACK_BOUNDARY = "ACTIVITY_CALLBACK_BOUNDARY"
		const val RUNTIME_VALIDATION_BOUNDARY = "ACTIVITY_RUNTIME_VALIDATION_BOUNDARY"
		const val LOCK_SUPPRESSION_BOUNDARY = "ACTIVITY_LOCK_SUPPRESSION_BOUNDARY"
		const val POWER_SAVER_SUPPRESSION_BOUNDARY = "ACTIVITY_POWER_SAVER_SUPPRESSION_BOUNDARY"
		const val PROCESS_RUNTIME_RECONCILIATION = "ACTIVITY_PROCESS_RUNTIME_RECONCILIATION"
	}
}
