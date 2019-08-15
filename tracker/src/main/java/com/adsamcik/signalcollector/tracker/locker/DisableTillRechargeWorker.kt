package com.adsamcik.signalcollector.tracker.locker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * JobService used for job that waits until device is connected to a charger to remove recharge lockTimeLock
 */
internal class DisableTillRechargeWorker(context: Context, workerParams: WorkerParameters) : Worker(context,
		workerParams) {
	override fun doWork(): Result {
		TrackerLocker.unlockRechargeLock(applicationContext)
		return Result.success()
	}
}
