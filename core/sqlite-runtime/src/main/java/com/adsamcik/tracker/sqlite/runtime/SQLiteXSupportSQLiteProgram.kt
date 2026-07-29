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
		delegate.bindNull(index)
	}

	override fun bindLong(index: Int, value: Long) {
		delegate.bindLong(index, value)
	}

	override fun bindDouble(index: Int, value: Double) {
		delegate.bindDouble(index, value)
	}

	override fun bindString(index: Int, value: String) {
		delegate.bindString(index, value)
	}

	override fun bindBlob(index: Int, value: ByteArray) {
		delegate.bindBlob(index, value)
	}

	override fun clearBindings() {
		delegate.clearBindings()
	}

	override fun close() {
		delegate.close()
	}
}
