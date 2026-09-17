package com.adsamcik.tracker.sqlite.runtime

import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Room-facing factory that selects the vendored SQLite 3.53.3 runtime.
 *
 * [preserveDatabaseFilesOnCorruption] is opt-in because it suppresses the configured Room
 * corruption callback and any data-loss recovery. The caller must surface and remediate failure.
 */
public class SQLiteXSupportSQLiteOpenHelperFactory @JvmOverloads constructor(
	private val preserveDatabaseFilesOnCorruption: Boolean = false,
) : SupportSQLiteOpenHelper.Factory {
	override fun create(
		configuration: SupportSQLiteOpenHelper.Configuration,
	): SupportSQLiteOpenHelper = SQLiteXSupportSQLiteOpenHelper(
		context = configuration.context,
		name = configuration.name,
		callback = configuration.callback,
		useNoBackupDirectory = configuration.useNoBackupDirectory,
		allowDataLossOnRecovery = configuration.allowDataLossOnRecovery,
		preserveDatabaseFilesOnCorruption = preserveDatabaseFilesOnCorruption,
	)
}
