package com.adsamcik.tracker.sqlite.runtime

import androidx.sqlite.db.SupportSQLiteStatement
import org.sqlite.database.sqlite.SQLiteStatement

/** Adapts a vendored [SQLiteStatement] to AndroidX's SupportSQLite contract. */
internal class SQLiteXSupportSQLiteStatement(
	private val delegate: SQLiteStatement,
) : SQLiteXSupportSQLiteProgram(delegate), SupportSQLiteStatement {

	override fun execute() {
		delegate.execute()
	}

	override fun executeUpdateDelete(): Int = delegate.executeUpdateDelete()

	override fun executeInsert(): Long = delegate.executeInsert()

	override fun simpleQueryForLong(): Long = delegate.simpleQueryForLong()

	override fun simpleQueryForString(): String? = delegate.simpleQueryForString()
}
