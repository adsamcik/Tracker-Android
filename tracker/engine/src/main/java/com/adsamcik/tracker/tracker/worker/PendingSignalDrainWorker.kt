package com.adsamcik.tracker.tracker.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.tracker.pipeline.persistence.PersistenceProcessor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class PendingSignalDrainWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted workerParameters: WorkerParameters,
	private val persistenceProcessor: PersistenceProcessor,
) : CoroutineWorker(context, workerParameters) {
	override suspend fun doWork(): Result =
		if (persistenceProcessor.drainOrphanedSignals()) Result.success() else Result.retry()
}

