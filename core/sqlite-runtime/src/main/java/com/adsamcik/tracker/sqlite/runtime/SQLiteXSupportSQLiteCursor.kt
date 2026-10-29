package com.adsamcik.tracker.sqlite.runtime

import org.sqlite.database.sqlite.SQLiteCursor
import org.sqlite.database.sqlite.SQLiteCursorDriver
import org.sqlite.database.sqlite.SQLiteQuery

/** Translates failures where the vendored cursor executes a query lazily, including internal moves. */
internal class SQLiteXSupportSQLiteCursor(driver: SQLiteCursorDriver, editTable: String?, query: SQLiteQuery) :
	SQLiteCursor(driver, editTable, query) {
	override fun getCount(): Int = sqliteXCall { super.getCount() }
	override fun onMove(oldPosition: Int, newPosition: Int): Boolean =
		sqliteXCall { super.onMove(oldPosition, newPosition) }
	override fun requery(): Boolean = sqliteXCall { super.requery() }
	override fun deactivate() = sqliteXCall { super.deactivate() }
	override fun close() = sqliteXCall { super.close() }
}
