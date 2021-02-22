package com.adsamcik.tracker.export

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.sqlite.db.SimpleSQLiteQuery
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.anggrayudi.storage.file.openInputStream
import com.anggrayudi.storage.file.openOutputStream

/**
 * Exports raw database to a desired location.
 */
class DatabaseExporter : Exporter {
	override val canSelectDateRange: Boolean = false

	override fun export(
			context: Context,
			locationData: List<DatabaseLocation>,
			destinationDirectory: DocumentFile,
			desiredName: String
	): ExportResult {
		val db = AppDatabase.database(context)
		val dbFile = DocumentFile.fromFile(context.getDatabasePath(db.openHelper.databaseName))
		val fileName = "$desiredName.db"
		val targetFile =
				destinationDirectory.findFile(fileName)
						?: destinationDirectory.createFile(mime, fileName)
						?: throw RuntimeException("Could not access or create file $fileName")

		db.generalDao().checkpoint(SimpleSQLiteQuery("pragma wal_checkpoint(full)"))
		db.runInTransaction {
			dbFile.openInputStream(context)?.use { input ->
				//todo check if file exists
				targetFile.openOutputStream(context, append = false)?.use { output ->
					input.copyTo(output)
				}
			}
			/*dbFile.copyTo(context, targetFile, callback = object : FileCopyCallback {
				override fun onCheckFreeSpace(freeSpace: Long, fileSize: Long): Boolean {
					return freeSpace > fileSize
				}

				override fun onFailed(errorCode: ErrorCode) {
					// todo notification
					Logger.log(
							LogData(
									message = "Failed export with code ${errorCode.name}",
									source = "export"
							)
					)
				}

				override fun onReport(progress: Float, bytesMoved: Long, writeSpeed: Int) {
					// todo notification
				}

				override fun onCompleted(file: Any): Boolean {
					return false
				}
			})*/
		}
		return ExportResult(targetFile, mime)
	}

	companion object {
		private const val mime = "application/vnd.sqlite3"
	}
}

