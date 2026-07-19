package com.adsamcik.tracker.shared.base.database.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal const val DATABASE_MIGRATION_BACKUP_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

interface DatabaseMigrationBackupRepository {
	val backups: Flow<DatabaseMigrationBackup?>

	fun latestBackup(): DatabaseMigrationBackup?

	fun exportLatest(outputStream: OutputStream): DatabaseMigrationBackup

	fun deleteAll()
}

data class DatabaseMigrationBackup(
	val file: File,
	val sourceVersion: Int,
	val targetVersion: Int,
	val createdAtMs: Long,
)

class DatabaseMigrationBackupException(
	message: String,
	cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Creates and validates a private, full-file SQLite backup before Room upgrades the main database.
 */
class DatabaseMigrationBackupStore(
	context: Context,
	private val backupDirectory: File = File(
		context.noBackupFilesDir,
		BACKUP_DIRECTORY_NAME,
	),
	private val nowMillis: () -> Long = System::currentTimeMillis,
	private val directorySync: (File) -> Unit = ::syncDirectory,
	private val scheduleExpiry: (DatabaseMigrationBackup) -> Unit = {
		DatabaseMigrationBackupCleanupWorker.schedule(context.applicationContext, it.createdAtMs)
	},
	private val cancelExpiry: () -> Unit = {
		DatabaseMigrationBackupCleanupWorker.cancel(context.applicationContext)
	},
) : DatabaseMigrationBackupRepository {
	private val appContext = context.applicationContext
	private val deletionMarker = File(
		backupDirectory.parentFile,
		"${backupDirectory.name}.delete-pending",
	)
	override val backups: Flow<DatabaseMigrationBackup?> = BACKUP_GENERATION
		.map { latestBackup() }
		.distinctUntilChanged()

	fun createBackupIfNeeded(
		databaseName: String,
		targetVersion: Int,
	): DatabaseMigrationBackup? = PROCESS_LOCK.withLock {
		reconcilePendingDeletion()
		pruneExpiredBackups()
		val source = appContext.getDatabasePath(databaseName)
		if (!source.isFile) return@withLock null

		try {
			val sourceVersion = checkpointAndReadVersion(source, targetVersion)
				?: return@withLock null
			val sourceHash = sha256(source)
			ensureBackupDirectory()
			val backup = backupFile(databaseName, sourceVersion, targetVersion, sourceHash)
			val temporary = File(backupDirectory, "${backup.nameWithoutExtension}.tmp.db")

			if (isValidBackup(backup, sourceVersion, sourceHash)) {
				directorySync(backupDirectory)
				pruneBackupsExcept(backup)
				return@withLock publishBackup(backup.toMetadata(sourceVersion, targetVersion))
			}
			if (isValidBackup(temporary, sourceVersion, sourceHash)) {
				promoteTemporaryBackup(temporary, backup)
				markCreatedNow(backup)
				pruneBackupsExcept(backup)
				return@withLock publishBackup(backup.toMetadata(sourceVersion, targetVersion))
			}

			temporary.delete()
			copyAndSync(source, temporary)
			validateBackup(temporary, sourceVersion, sourceHash)
			promoteTemporaryBackup(temporary, backup)
			markCreatedNow(backup)
			validateBackup(backup, sourceVersion, sourceHash)
			pruneBackupsExcept(backup)
			publishBackup(backup.toMetadata(sourceVersion, targetVersion))
		} catch (error: DatabaseMigrationBackupException) {
			throw error
		} catch (error: IOException) {
			throw DatabaseMigrationBackupException(
				"Could not create the required pre-migration database backup",
				error,
			)
		} catch (error: android.database.sqlite.SQLiteException) {
			throw DatabaseMigrationBackupException(
				"Could not validate the database before migration",
				error,
			)
		}
	}

	override fun latestBackup(): DatabaseMigrationBackup? = PROCESS_LOCK.withLock {
		reconcilePendingDeletion()
		pruneExpiredBackups()
		if (!backupDirectory.isDirectory) return@withLock null
		backupDirectory.listFiles()
			.orEmpty()
			.mapNotNull(::parseBackupCandidate)
			.filter {
				isValidBackup(
					it.backup.file,
					it.backup.sourceVersion,
					it.hashPrefix,
				)
			}
			.maxByOrNull { it.backup.createdAtMs }
			?.backup
	}

	override fun exportLatest(outputStream: OutputStream): DatabaseMigrationBackup =
		PROCESS_LOCK.withLock {
			val backup = latestBackup()
				?: throw DatabaseMigrationBackupException(
					"No valid pre-migration database backup exists",
				)
			backup.file.inputStream().use { input ->
				input.copyTo(outputStream)
			}
			backup
		}

	fun markDeletionPending() = PROCESS_LOCK.withLock {
		writeDeletionMarker()
	}

	override fun deleteAll() = PROCESS_LOCK.withLock {
		writeDeletionMarker()
		deleteBackupsAndMarker()
		cancelExpiry()
		notifyBackupChanged()
	}

	fun deleteExpiredBackups() = PROCESS_LOCK.withLock {
		reconcilePendingDeletion()
		if (pruneExpiredBackups()) {
			cancelExpiry()
			notifyBackupChanged()
		}
	}

	private fun checkpointAndReadVersion(source: File, targetVersion: Int): Int? {
		return SQLiteDatabase.openDatabase(
			source.path,
			null,
			SQLiteDatabase.OPEN_READWRITE,
		).use { database ->
			val version = database.version
			if (version <= 0 || version >= targetVersion) return@use null
			database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
				if (!cursor.moveToFirst() || cursor.getInt(0) != 0) {
					throw DatabaseMigrationBackupException(
						"Could not checkpoint the database before migration",
					)
				}
			}
			database.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor ->
				if (!cursor.moveToFirst() || !cursor.getString(0).equals("delete", ignoreCase = true)) {
					throw DatabaseMigrationBackupException(
						"Could not make the database portable before migration",
					)
				}
			}
			version
		}
	}

	private fun ensureBackupDirectory() {
		if (backupDirectory.isDirectory) return
		if (backupDirectory.exists() || !backupDirectory.mkdirs()) {
			throw DatabaseMigrationBackupException(
				"Could not create the private migration backup directory",
			)
		}
		directorySync(checkNotNull(backupDirectory.parentFile))
	}

	private fun copyAndSync(source: File, destination: File) {
		source.inputStream().use { input ->
			FileOutputStream(destination).use { output ->
				input.copyTo(output)
				output.fd.sync()
			}
		}
	}

	private fun promoteTemporaryBackup(temporary: File, backup: File) {
		if (backup.exists() && !backup.delete()) {
			throw DatabaseMigrationBackupException("Could not replace an invalid migration backup")
		}
		if (!temporary.renameTo(backup)) {
			throw DatabaseMigrationBackupException("Could not finalize the migration backup")
		}
		directorySync(backupDirectory)
	}

	private fun markCreatedNow(backup: File) {
		if (!backup.setLastModified(nowMillis())) {
			throw DatabaseMigrationBackupException("Could not timestamp the migration backup")
		}
		directorySync(backupDirectory)
	}

	private fun validateBackup(
		file: File,
		expectedVersion: Int,
		expectedHash: String? = null,
	) {
		val failure = backupValidationFailure(file, expectedVersion, expectedHash)
		if (failure != null) {
			throw DatabaseMigrationBackupException(
				"The pre-migration database backup failed validation: $failure",
			)
		}
	}

	private fun isValidBackup(
		file: File,
		expectedVersion: Int,
		expectedHash: String? = null,
	): Boolean = backupValidationFailure(file, expectedVersion, expectedHash) == null

	private fun backupValidationFailure(
		file: File,
		expectedVersion: Int,
		expectedHash: String? = null,
	): String? {
		if (!file.isFile) return "backup file does not exist"
		if (file.length() == 0L) return "backup file is empty"
		if (expectedHash != null) {
			val actualHash = sha256(file)
			val hashMatches = if (expectedHash.length == SHA_256_LENGTH) {
				actualHash == expectedHash
			} else {
				actualHash.startsWith(expectedHash)
			}
			if (!hashMatches) return "backup bytes differ from the checkpointed source"
		}
		return try {
			SQLiteDatabase.openDatabase(
				file.path,
				null,
				SQLiteDatabase.OPEN_READONLY,
			).use { database ->
				if (database.version != expectedVersion) {
					return@use "expected schema v$expectedVersion but found v${database.version}"
				}
				database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
					if (!cursor.moveToFirst()) {
						"integrity_check returned no result"
					} else {
						cursor.getString(0)
							.takeUnless { it.equals("ok", ignoreCase = true) }
							?.let { "integrity_check returned $it" }
					}
				}
			}
		} catch (error: android.database.sqlite.SQLiteException) {
			"SQLite could not open the backup: ${error.message}"
		}
	}

	private fun writeDeletionMarker() {
		val parent = checkNotNull(deletionMarker.parentFile)
		if (!parent.isDirectory && !parent.mkdirs()) {
			throw DatabaseMigrationBackupException("Could not prepare backup deletion")
		}
		val temporary = File(parent, "${deletionMarker.name}.tmp")
		FileOutputStream(temporary).use { output ->
			output.write("pending".encodeToByteArray())
			output.fd.sync()
		}
		if (deletionMarker.exists() && !deletionMarker.delete()) {
			throw DatabaseMigrationBackupException("Could not replace backup deletion marker")
		}
		if (!temporary.renameTo(deletionMarker)) {
			throw DatabaseMigrationBackupException("Could not persist backup deletion marker")
		}
		directorySync(parent)
	}

	private fun reconcilePendingDeletion() {
		if (deletionMarker.exists()) {
			deleteBackupsAndMarker()
		}
	}

	private fun deleteBackupsAndMarker() {
		if (backupDirectory.exists() && !backupDirectory.deleteRecursively()) {
			throw DatabaseMigrationBackupException("Could not delete private migration backups")
		}
		val parent = checkNotNull(deletionMarker.parentFile)
		directorySync(parent)
		if (deletionMarker.exists() && !deletionMarker.delete()) {
			throw DatabaseMigrationBackupException("Could not clear backup deletion marker")
		}
		directorySync(parent)
	}

	private fun pruneExpiredBackups(): Boolean {
		if (!backupDirectory.isDirectory) return false
		val cutoff = nowMillis() - DATABASE_MIGRATION_BACKUP_RETENTION_MS
		var changed = false
		backupDirectory.listFiles().orEmpty().forEach { file ->
			if (file.lastModified() < cutoff && !file.delete()) {
				throw DatabaseMigrationBackupException("Could not delete an expired migration backup")
			}
			changed = changed || !file.exists()
		}
		if (changed) directorySync(backupDirectory)
		return changed
	}

	private fun pruneBackupsExcept(kept: File) {
		var changed = false
		backupDirectory.listFiles().orEmpty().forEach { file ->
			if (file != kept && !file.name.endsWith(".tmp.db") && !file.delete()) {
				throw DatabaseMigrationBackupException("Could not rotate an older migration backup")
			}
			changed = changed || !file.exists()
		}
		if (changed) directorySync(backupDirectory)
	}

	private fun backupFile(
		databaseName: String,
		sourceVersion: Int,
		targetVersion: Int,
		sourceHash: String,
	): File = File(
		backupDirectory,
		"$databaseName-v$sourceVersion-pre-v$targetVersion-${sourceHash.take(HASH_PREFIX_LENGTH)}.db",
	)

	private fun parseBackupCandidate(file: File): BackupCandidate? {
		val match = BACKUP_NAME.matchEntire(file.name) ?: return null
		return BackupCandidate(
			backup = DatabaseMigrationBackup(
				file = file,
				sourceVersion = match.groupValues[2].toIntOrNull() ?: return null,
				targetVersion = match.groupValues[3].toIntOrNull() ?: return null,
				createdAtMs = file.lastModified(),
			),
			hashPrefix = match.groupValues[4],
		)
	}

	private fun File.toMetadata(
		sourceVersion: Int,
		targetVersion: Int,
	): DatabaseMigrationBackup = DatabaseMigrationBackup(
		file = this,
		sourceVersion = sourceVersion,
		targetVersion = targetVersion,
		createdAtMs = lastModified(),
	)

	private fun sha256(file: File): String {
		val digest = MessageDigest.getInstance("SHA-256")
		file.inputStream().use { input ->
			val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
			while (true) {
				val count = input.read(buffer)
				if (count < 0) break
				digest.update(buffer, 0, count)
			}
		}
		return digest.digest().joinToString("") { "%02x".format(it) }
	}

	private fun publishBackup(backup: DatabaseMigrationBackup): DatabaseMigrationBackup {
		scheduleExpiry(backup)
		notifyBackupChanged()
		return backup
	}

	private fun notifyBackupChanged() {
		BACKUP_GENERATION.value += 1
	}

	private companion object {
		const val BACKUP_DIRECTORY_NAME = "database-migration-backups"
		const val HASH_PREFIX_LENGTH = 16
		const val SHA_256_LENGTH = 64
		val BACKUP_NAME = Regex("(.+)-v(\\d+)-pre-v(\\d+)-([0-9a-f]{16})\\.db")
		val PROCESS_LOCK = ReentrantLock()
		val BACKUP_GENERATION = MutableStateFlow(0L)

		fun syncDirectory(directory: File) {
			val descriptor = Os.open(
				directory.path,
				OsConstants.O_RDONLY,
				0,
			)
			try {
				Os.fsync(descriptor)
			} finally {
				Os.close(descriptor)
			}
		}

		private data class BackupCandidate(
			val backup: DatabaseMigrationBackup,
			val hashPrefix: String,
		)
	}
}
