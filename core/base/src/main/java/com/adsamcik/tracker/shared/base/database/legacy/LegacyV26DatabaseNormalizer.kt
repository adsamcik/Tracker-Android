package com.adsamcik.tracker.shared.base.database.legacy

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.adsamcik.tracker.sqlite.runtime.SQLiteXSupportSQLiteOpenHelperFactory
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

@SuppressLint("UsableSpace")
private fun bestEffortUsableSpace(directory: File): Long = directory.usableSpace

internal data class PreparedLegacyDatabase(
	val file: File,
	val originalVersion: Int,
	private val temporaryFiles: List<File>,
) : Closeable {
	override fun close() {
		// A staging cleanup failure must not roll back an otherwise valid import. The fixed staging
		// name is cleared before every later attempt, so cleanup remains naturally retryable.
		temporaryFiles.forEach { file -> runCatching { file.delete() } }
	}
}

/** Normalizes older publicly shipped schemas on one disposable copy, never on the vault. */
internal class LegacyV26DatabaseNormalizer(
	context: Context,
	private val repository: LegacyDatabaseRepository,
	private val publicMigrations: Array<Migration>,
	private val openHelperFactory: SupportSQLiteOpenHelper.Factory =
		SQLiteXSupportSQLiteOpenHelperFactory(),
	// This is only an early, side-effect-free preflight. The copy remains authoritative and reports
	// allocation/I/O failures without asking Android to evict caches or reserve storage for us.
	private val usableSpaceBytes: (File) -> Long = ::bestEffortUsableSpace,
) {
	private val appContext = context.applicationContext

	fun prepare(): PreparedLegacyDatabase {
		val info = repository.prepareForRead()
		if (info.sourceVersion == RELEASED_DATABASE_VERSION) {
			return PreparedLegacyDatabase(info.file, info.sourceVersion, emptyList())
		}
		if (info.sourceVersion !in oldestSupportedVersion() until RELEASED_DATABASE_VERSION) {
			throw LegacyDatabaseException(
				"Legacy schema v${info.sourceVersion} is not a supported public database",
			)
		}

		val staging = appContext.getDatabasePath(STAGING_DATABASE_NAME)
		val family = databaseFamily(staging)
		family.forEach { file ->
			if (file.exists() && !file.delete()) {
				throw LegacyDatabaseException("Could not reset temporary legacy database ${file.name}")
			}
		}
		staging.parentFile?.let { parent ->
			if (!parent.isDirectory && !parent.mkdirs()) {
				throw LegacyDatabaseException("Could not prepare the database directory")
			}
		}
		val requiredBytes = info.file.length() + MIN_FREE_SPACE_BYTES
		if (staging.parentFile?.let(usableSpaceBytes)?.let { it < requiredBytes } == true) {
			throw LegacyDatabaseException("There is not enough free space to prepare the legacy import")
		}
		info.file.inputStream().use { input ->
			FileOutputStream(staging).use { output ->
				input.copyTo(output)
				output.fd.sync()
			}
		}

		try {
			migrateStagingDatabase(info.sourceVersion)
			validate(staging)
		} catch (error: Throwable) {
			family.forEach { file -> runCatching { file.delete() } }
			if (error is LegacyDatabaseException) throw error
			throw LegacyDatabaseException("Could not normalize the legacy database to v26", error)
		}
		return PreparedLegacyDatabase(staging, info.sourceVersion, family)
	}

	private fun migrateStagingDatabase(sourceVersion: Int) {
		val callback = object : SupportSQLiteOpenHelper.Callback(RELEASED_DATABASE_VERSION) {
			override fun onCreate(db: SupportSQLiteDatabase) {
				throw LegacyDatabaseException("The temporary legacy database was unexpectedly empty")
			}

			override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
				if (oldVersion != sourceVersion || newVersion != RELEASED_DATABASE_VERSION) {
					throw LegacyDatabaseException(
						"Unexpected legacy normalization $oldVersion to $newVersion",
					)
				}
				var current = oldVersion
				while (current < newVersion) {
					val migration = publicMigrations.singleOrNull { it.startVersion == current }
						?: throw LegacyDatabaseException("No public migration starts at schema v$current")
					if (migration.endVersion > newVersion) {
						throw LegacyDatabaseException("Public migration v$current crosses the v26 boundary")
					}
					migration.migrate(db)
					current = migration.endVersion
				}
			}
		}
		val helper = openHelperFactory.create(
			SupportSQLiteOpenHelper.Configuration.builder(appContext)
				.name(STAGING_DATABASE_NAME)
				.callback(callback)
				.build(),
		)
		try {
			helper.writableDatabase
		} finally {
			helper.close()
		}
	}

	private fun validate(file: File) {
		SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
			if (database.version != RELEASED_DATABASE_VERSION) {
				throw LegacyDatabaseException(
					"Expected normalized schema v26 but found v${database.version}",
				)
			}
			database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
				if (!cursor.moveToFirst() || !cursor.getString(0).equals("ok", ignoreCase = true)) {
					throw LegacyDatabaseException("The normalized legacy database failed integrity_check")
				}
			}
		}
	}

	private fun oldestSupportedVersion(): Int = publicMigrations.minOf(Migration::startVersion)

	private companion object {
		const val STAGING_DATABASE_NAME = "legacy_import_v26_staging"
		const val RELEASED_DATABASE_VERSION = 26
		const val MIN_FREE_SPACE_BYTES = 10L * 1024L * 1024L

		fun databaseFamily(file: File): List<File> = listOf(
			file,
			File(file.path + "-wal"),
			File(file.path + "-shm"),
			File(file.path + "-journal"),
		)
	}
}
