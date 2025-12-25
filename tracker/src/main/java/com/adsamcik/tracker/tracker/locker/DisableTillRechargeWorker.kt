package com.adsamcik.tracker.tracker.locker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.tracker.controller.LockManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * JobService used for job that waits until device is connected to a charger to remove recharge lockTimeLock
 */
@HiltWorker
internal class DisableTillRechargeWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted workerParams: WorkerParameters,
	private val lockManager: LockManager
) : Worker(context, workerParams) {
	override fun doWork(): Result {
		lockManager.unlockRechargeLock(applicationContext)
		return Result.success()
	}
}

