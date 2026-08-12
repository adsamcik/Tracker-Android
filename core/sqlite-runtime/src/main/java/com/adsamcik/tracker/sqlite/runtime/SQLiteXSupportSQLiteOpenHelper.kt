package com.adsamcik.tracker.sqlite.runtime

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.io.File
import org.sqlite.database.DatabaseErrorHandler
import org.sqlite.database.sqlite.SQLiteDatabase as VendorSQLiteDatabase
import org.sqlite.database.sqlite.SQLiteException as VendorSQLiteException
import org.sqlite.database.sqlite.SQLiteOpenHelper as VendorSQLiteOpenHelper

/**
 * A SupportSQLiteOpenHelper backed by the official `org.sqlite` Android binding.
 *
 * Opening is intentionally lazy. Constructing a Room database therefore does
 * not perform file I/O or load native code; both happen only when Room asks for
 * a readable or writable database.
 */
public class SQLiteXSupportSQLiteOpenHelper
@JvmOverloads
constructor(
	private val context: Context,
	private val name: String?,
	private val callback: SupportSQLiteOpenHelper.Callback,
	private val useNoBackupDirectory: Boolean = false,
	private val allowDataLossOnRecovery: Boolean = false,
) : SupportSQLiteOpenHelper {

	private val lazyDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
		SQLiteXRuntime.ensureLoaded()
		OpenHelper(
			context = context,
			name = resolvedName(),
			dbRef = DatabaseReference(),
			callback = callback,
			allowDataLossOnRecovery = allowDataLossOnRecovery,
		).also { helper ->
			helper.setRequestedWriteAheadLoggingEnabled(writeAheadLoggingEnabled)
		}
	}

	private val delegate: OpenHelper by lazyDelegate

	private var writeAheadLoggingEnabled: Boolean = false

	override val databaseName: String?
		get() = name

	override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
		synchronized(this) {
			if (lazyDelegate.isInitialized()) {
				delegate.setRequestedWriteAheadLoggingEnabled(enabled)
			}
			writeAheadLoggingEnabled = enabled
		}
	}

	override val writableDatabase: SupportSQLiteDatabase
		get() = delegate.getSupportDatabase(writable = true)

	override val readableDatabase: SupportSQLiteDatabase
		get() = delegate.getSupportDatabase(writable = false)

	override fun close() {
		synchronized(this) {
			if (lazyDelegate.isInitialized()) {
				delegate.close()
			}
		}
	}

	private fun resolvedName(): String? {
		val databaseName = name ?: return null
		return if (useNoBackupDirectory) {
			File(context.noBackupFilesDir, databaseName).absolutePath
		} else {
			databaseName
		}
	}

	private class OpenHelper(
		private val context: Context,
		private val name: String?,
		private val dbRef: DatabaseReference,
		private val callback: SupportSQLiteOpenHelper.Callback,
		private val allowDataLossOnRecovery: Boolean,
	) : VendorSQLiteOpenHelper(
		context,
		name,
		null,
		callback.version,
		DatabaseErrorHandler { database ->
			SQLiteXRuntime.ensureLoaded()
			callback.onCorruption(getWrappedDatabase(dbRef, database))
		},
	) {
		private var migrated = false
		private var requestedWriteAheadLoggingEnabled = false

		@Synchronized
		fun setRequestedWriteAheadLoggingEnabled(enabled: Boolean) {
			requestedWriteAheadLoggingEnabled = enabled
			super.setWriteAheadLoggingEnabled(enabled)
		}

		@Synchronized
		fun getSupportDatabase(writable: Boolean): SupportSQLiteDatabase {
			SQLiteXRuntime.ensureLoaded()
			ensureParentDirectory()
			migrated = false
			val database = getDatabaseWithRecovery(writable)
			if (migrated) {
				// The migration callback may have left a pooled connection with stale
				// schema metadata. Re-open before publishing the SupportSQLite facade.
				close()
				return getSupportDatabase(writable)
			}
			return getWrappedDatabase(dbRef, database)
		}

		private fun getDatabaseWithRecovery(writable: Boolean): VendorSQLiteDatabase {
			var failure: Throwable
			try {
				return openDatabase(writable)
			} catch (caught: Throwable) {
				failure = caught
			}

			// Match AndroidX's bounded second attempt for transient storage races.
			try {
				Thread.sleep(OPEN_RETRY_DELAY_MILLIS)
			} catch (_: InterruptedException) {
				Thread.currentThread().interrupt()
			}
			try {
				return openDatabase(writable)
			} catch (caught: Throwable) {
				failure = caught
			}

			return recoverOrThrow(failure, writable)
		}

		private fun recoverOrThrow(failure: Throwable, writable: Boolean): VendorSQLiteDatabase {
			val callbackFailure = failure as? CallbackException
			val originalFailure = callbackFailure?.cause ?: failure
			if (
				callbackFailure != null &&
				callbackFailure.stage != CallbackStage.ON_OPEN
			) {
				throw originalFailure
			}
			if (
				originalFailure !is VendorSQLiteException ||
				name == null ||
				!allowDataLossOnRecovery
			) {
				throw originalFailure
			}

			close()
			val databaseFile = databaseFile()
			if (!VendorSQLiteDatabase.deleteDatabase(databaseFile)) {
				throw originalFailure
			}

			return try {
				openDatabase(writable)
			} catch (callbackError: CallbackException) {
				throw callbackError.cause
			}
		}

		private fun openDatabase(writable: Boolean): VendorSQLiteDatabase =
			if (writable) super.getWritableDatabase() else super.getReadableDatabase()

		private fun ensureParentDirectory() {
			val databaseName = name ?: return
			if (databaseName.startsWith(FILE_URI_PREFIX)) return
			val parent = databaseFile().parentFile ?: return
			if (!parent.exists()) {
				parent.mkdirs()
			}
		}

		private fun databaseFile(): File {
			val databaseName = checkNotNull(name)
			val explicitFile = File(databaseName)
			return if (explicitFile.isAbsolute) explicitFile else context.getDatabasePath(databaseName)
		}

		override fun onConfigure(database: VendorSQLiteDatabase) {
			applyRequestedWriteAheadLoggingMode(database)
			if (!migrated && callback.version != database.version) {
				// Avoid stale cached statements while schema migration callbacks run.
				database.setMaxSqlCacheSize(1)
			}
			invokeCallback(CallbackStage.ON_CONFIGURE) {
				callback.onConfigure(getWrappedDatabase(dbRef, database))
			}
		}

		private fun applyRequestedWriteAheadLoggingMode(database: VendorSQLiteDatabase) {
			if (
				database.isReadOnly ||
				database.isWriteAheadLoggingEnabled == requestedWriteAheadLoggingEnabled
			) {
				return
			}
			if (requestedWriteAheadLoggingEnabled) {
				check(database.enableWriteAheadLogging()) {
					"SQLiteX could not enable write-ahead logging before database callbacks"
				}
			} else {
				database.disableWriteAheadLogging()
			}
		}

		override fun onCreate(database: VendorSQLiteDatabase) {
			invokeCallback(CallbackStage.ON_CREATE) {
				callback.onCreate(getWrappedDatabase(dbRef, database))
			}
		}

		override fun onUpgrade(database: VendorSQLiteDatabase, oldVersion: Int, newVersion: Int) {
			migrated = true
			invokeCallback(CallbackStage.ON_UPGRADE) {
				callback.onUpgrade(getWrappedDatabase(dbRef, database), oldVersion, newVersion)
			}
		}

		override fun onDowngrade(database: VendorSQLiteDatabase, oldVersion: Int, newVersion: Int) {
			migrated = true
			invokeCallback(CallbackStage.ON_DOWNGRADE) {
				callback.onDowngrade(getWrappedDatabase(dbRef, database), oldVersion, newVersion)
			}
		}

		override fun onOpen(database: VendorSQLiteDatabase) {
			if (!migrated) {
				invokeCallback(CallbackStage.ON_OPEN) {
					callback.onOpen(getWrappedDatabase(dbRef, database))
				}
			}
		}

		override fun close() {
			try {
				super.close()
			} finally {
				dbRef.database = null
			}
		}

		private fun invokeCallback(stage: CallbackStage, block: () -> Unit) {
			try {
				block()
			} catch (failure: Throwable) {
				throw CallbackException(stage, failure)
			}
		}

		private class CallbackException(
			val stage: CallbackStage,
			override val cause: Throwable,
		) : RuntimeException(cause)

		private enum class CallbackStage {
			ON_CONFIGURE,
			ON_CREATE,
			ON_UPGRADE,
			ON_DOWNGRADE,
			ON_OPEN,
		}

		private companion object {
			private const val FILE_URI_PREFIX = "file:"
			private const val OPEN_RETRY_DELAY_MILLIS = 500L
		}
	}

	private class DatabaseReference(
		var database: SQLiteXSupportSQLiteDatabase? = null,
	)

	private companion object {
		fun getWrappedDatabase(
			databaseReference: DatabaseReference,
			database: VendorSQLiteDatabase,
		): SQLiteXSupportSQLiteDatabase {
			val cached = databaseReference.database
			return if (cached == null || !cached.isDelegate(database)) {
				SQLiteXSupportSQLiteDatabase(database).also { databaseReference.database = it }
			} else {
				cached
			}
		}
	}
}
