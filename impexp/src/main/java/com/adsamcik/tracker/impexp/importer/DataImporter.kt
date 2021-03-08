package com.adsamcik.tracker.impexp.importer

import android.content.Context
import android.net.Uri
import com.adsamcik.tracker.impexp.importer.service.ImportService
import com.adsamcik.tracker.shared.base.extension.startForegroundService

/**
 * Exposes import start to other packages.
 */
object DataImporter {
	fun import(context: Context, uri: Uri) {
		context.startForegroundService<ImportService> {
			putExtra(ImportService.ARG_FILE_URI, uri)
		}
	}
}
