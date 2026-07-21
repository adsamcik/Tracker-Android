package com.adsamcik.tracker.shared.base.database

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.logging.api.ReporterFacade
import java.io.File

/**
 * Low-frequency runtime evidence for the SQLite engine and WAL configuration actually in use.
 *
 * The catalog version is only the requested dependency. Device ABI selection, packaging, and the
 * open-helper implementation determine the binary that is ultimately loaded, so durability
 * diagnostics must query the open database. This observer deliberately does not change any PRAGMA
 * and never logs a database path.
 */
internal object SQLiteRuntimeTelemetry {

	private const val TAG = "TrackerSQLite"
	private const val SOURCE = "sqlite-runtime"

	/**
	 * Collects an open-time snapshot without allowing a diagnostics failure to affect database use.
	 *
	 * A passive checkpoint is non-blocking. Its result exposes whether readers prevented complete
	 * checkpointing and how many WAL frames were available at the instant the database opened. The
	 * WAL file size is sampled before that query, so it reflects the backlog seen on open.
	 */
	fun recordOnOpen(
		database: SupportSQLiteDatabase,
		databaseType: String,
		databaseFile: File,
		emit: (String) -> Unit = ::emit,
	) {
		val message = try {
			collect(database, databaseFile).toLogLine(databaseType)
		} catch (ignored: Exception) {
			// A database must remain available even if a vendor implementation rejects a diagnostic.
			"event=sqlite_runtime_unavailable database=$databaseType " +
				"error=${ignored.javaClass.simpleName}"
		}
		// A third-party diagnostic sink must not be able to make a Room open fail.
		try {
			emit(message)
		} catch (_: Exception) {
			// Diagnostics are intentionally best-effort.
		}
	}

	internal fun collect(
		database: SupportSQLiteDatabase,
		databaseFile: File,
	): Snapshot {
		val unavailable = mutableListOf<String>()
		val journalMode = queryString(database, "PRAGMA journal_mode", "journal_mode", unavailable)

		return Snapshot(
			sqliteVersion = queryString(
				database,
				"SELECT sqlite_version()",
				"sqlite_version",
				unavailable,
			),
			sqliteSourceId = queryString(
				database,
				"SELECT sqlite_source_id()",
				"sqlite_source_id",
				unavailable,
			),
			journalMode = journalMode,
			synchronous = queryString(database, "PRAGMA synchronous", "synchronous", unavailable),
			walAutoCheckpoint = queryString(
				database,
				"PRAGMA wal_autocheckpoint",
				"wal_autocheckpoint",
				unavailable,
			),
			databaseBytes = databaseFile.length(),
			walBytes = File("${databaseFile.path}-wal").length(),
			checkpoint = if (journalMode.equals("wal", ignoreCase = true)) {
				queryCheckpoint(database, unavailable)
			} else {
				null
			},
			unavailable = unavailable.distinct(),
		)
	}

	private fun queryString(
		database: SupportSQLiteDatabase,
		sql: String,
		name: String,
		unavailable: MutableList<String>,
	): String? = try {
		database.query(sql).use { cursor ->
			if (!cursor.moveToFirst() || cursor.isNull(0)) {
				unavailable += name
				null
			} else {
				cursor.getString(0)
			}
		}
	} catch (_: Exception) {
		unavailable += name
		null
	}

	private fun queryCheckpoint(
		database: SupportSQLiteDatabase,
		unavailable: MutableList<String>,
	): Checkpoint? = try {
		database.query("PRAGMA wal_checkpoint(PASSIVE)").use { cursor ->
			if (!cursor.moveToFirst() || cursor.columnCount < CHECKPOINT_COLUMN_COUNT) {
				unavailable += "wal_checkpoint"
				null
			} else {
				Checkpoint(
					busy = cursor.getLong(CHECKPOINT_BUSY_COLUMN),
					walFrames = cursor.getLong(CHECKPOINT_WAL_FRAMES_COLUMN),
					checkpointedFrames = cursor.getLong(CHECKPOINTED_FRAMES_COLUMN),
				)
			}
		}
	} catch (_: Exception) {
		unavailable += "wal_checkpoint"
		null
	}

	private fun emit(message: String) {
		// Logcat is available before app logging initializes. If the app diagnostic facade has
		// already installed a delegate, it also receives the same low-severity event.
		Log.i(TAG, message)
		ReporterFacade.info(SOURCE, message)
	}

	internal data class Snapshot(
		val sqliteVersion: String?,
		val sqliteSourceId: String?,
		val journalMode: String?,
		val synchronous: String?,
		val walAutoCheckpoint: String?,
		val databaseBytes: Long,
		val walBytes: Long,
		val checkpoint: Checkpoint?,
		val unavailable: List<String>,
	) {
		fun toLogLine(databaseType: String): String = buildString {
			append("event=sqlite_runtime database=").append(databaseType)
			append(" sqlite_version=").append(sqliteVersion.orUnavailable())
			append(" sqlite_source_id=").append(sqliteSourceId.orUnavailable())
			append(" journal_mode=").append(journalMode.orUnavailable())
			append(" synchronous=").append(synchronous.orUnavailable())
			append(" wal_autocheckpoint=").append(walAutoCheckpoint.orUnavailable())
			append(" database_bytes=").append(databaseBytes)
			append(" wal_bytes=").append(walBytes)
			checkpoint?.let {
				append(" wal_checkpoint_busy=").append(it.busy)
				append(" wal_frames=").append(it.walFrames)
				append(" wal_checkpointed_frames=").append(it.checkpointedFrames)
			}
			if (unavailable.isNotEmpty()) {
				append(" unavailable=").append(unavailable.joinToString(","))
			}
		}
	}

	internal data class Checkpoint(
		val busy: Long,
		val walFrames: Long,
		val checkpointedFrames: Long,
	)

	private fun String?.orUnavailable(): String = this ?: "unavailable"

	private const val CHECKPOINT_BUSY_COLUMN = 0
	private const val CHECKPOINT_WAL_FRAMES_COLUMN = 1
	private const val CHECKPOINTED_FRAMES_COLUMN = 2
	private const val CHECKPOINT_COLUMN_COUNT = 3
}
