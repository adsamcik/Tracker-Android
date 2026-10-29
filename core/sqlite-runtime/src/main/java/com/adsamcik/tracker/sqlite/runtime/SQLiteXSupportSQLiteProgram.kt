package com.adsamcik.tracker.sqlite.runtime

import androidx.sqlite.db.SupportSQLiteProgram
import org.sqlite.database.sqlite.SQLiteProgram

/** Adapts a vendored [SQLiteProgram] to AndroidX's SupportSQLite contract. */
internal open class SQLiteXSupportSQLiteProgram(
	private val delegate: SQLiteProgram,
) : SupportSQLiteProgram {

	init {
		SQLiteXRuntime.ensureLoaded()
	}

	override fun bindNull(index: Int) {
		sqliteXCall { delegate.bindNull(index) }
	}

	override fun bindLong(index: Int, value: Long) {
		sqliteXCall { delegate.bindLong(index, value) }
	}

	override fun bindDouble(index: Int, value: Double) {
		sqliteXCall { delegate.bindDouble(index, value) }
	}

	override fun bindString(index: Int, value: String) {
		sqliteXCall { delegate.bindString(index, value) }
	}

	override fun bindBlob(index: Int, value: ByteArray) {
		sqliteXCall { delegate.bindBlob(index, value) }
	}

	override fun clearBindings() {
		sqliteXCall { delegate.clearBindings() }
	}

	override fun close() {
		sqliteXCall { delegate.close() }
	}
}
