package com.adsamcik.tracker.shared.base.database.migration

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Ensures the validated source backup exists before the delegate can run a Room migration.
 */
class MigrationBackupOpenHelperFactory(
	private val delegate: SupportSQLiteOpenHelper.Factory,
	private val backupStore: DatabaseMigrationBackupStore,
	private val databaseName: String,
	private val targetVersion: Int,
) : SupportSQLiteOpenHelper.Factory {

	override fun create(
		configuration: SupportSQLiteOpenHelper.Configuration,
	): SupportSQLiteOpenHelper {
		val helper = delegate.create(configuration)
		return BackupOpenHelper(helper)
	}

	private inner class BackupOpenHelper(
		private val delegate: SupportSQLiteOpenHelper,
	) : SupportSQLiteOpenHelper by delegate {
		@Volatile
		private var backupChecked = false

		override val writableDatabase: SupportSQLiteDatabase
			get() {
				ensureBackup()
				return delegate.writableDatabase
			}

		override val readableDatabase: SupportSQLiteDatabase
			get() {
				ensureBackup()
				return delegate.readableDatabase
			}

		@Synchronized
		private fun ensureBackup() {
			if (backupChecked) return
			backupStore.createBackupIfNeeded(
				this@MigrationBackupOpenHelperFactory.databaseName,
				targetVersion,
			)
			backupChecked = true
		}
	}
}
