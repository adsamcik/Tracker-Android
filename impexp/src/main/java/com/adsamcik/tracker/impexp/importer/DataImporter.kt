package com.adsamcik.tracker.impexp.importer

import android.content.Context
import android.net.Uri
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.adsamcik.tracker.impexp.importer.service.ImportService
import com.adsamcik.tracker.impexp.importer.worker.ImportWorker
import com.adsamcik.tracker.shared.base.extension.startForegroundService

/**
 * Exposes import start to other packages.
 */
object DataImporter {
	fun import(context: Context, fileUri: Uri) {
		val workRequest = OneTimeWorkRequestBuilder<ImportWorker>()
			.setInputData(workDataOf(ImportWorker.ARG_FILE_URI to fileUri.toString()))
			.build()
		WorkManager.getInstance(context).enqueue(workRequest)
	}
}
