package com.adsamcik.tracker.impexp.importer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.adsamcik.tracker.impexp.importer.worker.ImportWorker
import com.adsamcik.tracker.logger.Reporter

/**
 * Exposes import start to other packages.
 */
object DataImporter {
	fun import(context: Context, fileUri: Uri) {
		persistReadPermission(context, fileUri)
		val workRequest = OneTimeWorkRequestBuilder<ImportWorker>()
			.setInputData(workDataOf(ImportWorker.ARG_FILE_URI to fileUri.toString()))
			.build()
		WorkManager.getInstance(context).enqueue(workRequest)
	}

	internal fun persistReadPermission(context: Context, fileUri: Uri): Boolean {
		if (fileUri.scheme != ContentResolver.SCHEME_CONTENT) return false

		return runCatching {
			context.contentResolver.takePersistableUriPermission(
				fileUri,
				Intent.FLAG_GRANT_READ_URI_PERMISSION
			)
			true
		}.getOrElse {
			Reporter.w(IMPORT_LOG_SOURCE, "Unable to persist import read permission; continuing with available grant")
			false
		}
	}

	private const val IMPORT_LOG_SOURCE = "import"
}
