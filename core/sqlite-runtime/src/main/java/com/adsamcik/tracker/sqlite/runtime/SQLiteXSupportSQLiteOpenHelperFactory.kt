package com.adsamcik.tracker.sqlite.runtime

import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Room-facing factory that selects the vendored SQLite 3.53.3 runtime.
 */
public class SQLiteXSupportSQLiteOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
	override fun create(
		configuration: SupportSQLiteOpenHelper.Configuration,
	): SupportSQLiteOpenHelper = SQLiteXSupportSQLiteOpenHelper(
		context = configuration.context,
		name = configuration.name,
		callback = configuration.callback,
		useNoBackupDirectory = configuration.useNoBackupDirectory,
		allowDataLossOnRecovery = configuration.allowDataLossOnRecovery,
	)
}
