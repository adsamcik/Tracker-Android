package com.adsamcik.tracker.sqlite.runtime

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteTransactionListener
import android.os.CancellationSignal
import android.text.TextUtils
import android.util.Pair
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import java.util.Locale
import org.sqlite.database.sqlite.SQLiteCursor as VendorSQLiteCursor
import org.sqlite.database.sqlite.SQLiteDatabase as VendorSQLiteDatabase
import org.sqlite.database.sqlite.SQLiteQuery as VendorSQLiteQuery
import org.sqlite.database.sqlite.SQLiteTransactionListener as VendorSQLiteTransactionListener

/**
 * AndroidX SupportSQLite facade over the official SQLite Android binding.
 *
 * Vendor types never cross this class's public API. The sole exception is
 * implementation detail inside this module, where framework transaction
 * listeners are adapted to the binding's namespace-equivalent listener.
 */
internal class SQLiteXSupportSQLiteDatabase(
	private val delegate: VendorSQLiteDatabase,
) : SupportSQLiteDatabase {

	init {
		SQLiteXRuntime.ensureLoaded()
	}

	override fun compileStatement(sql: String): SupportSQLiteStatement =
		SQLiteXSupportSQLiteStatement(delegate.compileStatement(sql))

	override fun beginTransaction() {
		delegate.beginTransaction()
	}

	override fun beginTransactionNonExclusive() {
		delegate.beginTransactionNonExclusive()
	}

	/**
	 * The vendored public database API does not expose a read-only transaction
	 * entry point. SupportSQLite explicitly permits this fallback, so preserve
	 * transaction semantics by using the vendor's exclusive transaction method.
	 */
	override fun beginTransactionReadOnly() {
		delegate.beginTransaction()
	}

	override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) {
		delegate.beginTransactionWithListener(TransactionListenerAdapter(transactionListener))
	}

	override fun beginTransactionWithListenerNonExclusive(
		transactionListener: SQLiteTransactionListener,
	) {
		delegate.beginTransactionWithListenerNonExclusive(TransactionListenerAdapter(transactionListener))
	}

	override fun beginTransactionWithListenerReadOnly(
		transactionListener: SQLiteTransactionListener,
	) {
		delegate.beginTransactionWithListener(TransactionListenerAdapter(transactionListener))
	}

	override fun endTransaction() {
		delegate.endTransaction()
	}

	override fun setTransactionSuccessful() {
		delegate.setTransactionSuccessful()
	}

	override fun inTransaction(): Boolean = delegate.inTransaction()

	override val isDbLockedByCurrentThread: Boolean
		get() = delegate.isDbLockedByCurrentThread

	override fun yieldIfContendedSafely(): Boolean = delegate.yieldIfContendedSafely()

	override fun yieldIfContendedSafely(sleepAfterYieldDelayMillis: Long): Boolean =
		delegate.yieldIfContendedSafely(sleepAfterYieldDelayMillis)

	override val isExecPerConnectionSQLSupported: Boolean
		get() = false

	override fun execPerConnectionSQL(sql: String, bindArgs: Array<out Any?>?) {
		throw UnsupportedOperationException(
			"The official SQLite Android binding does not expose per-connection SQL execution.",
		)
	}

	override var version: Int
		get() = delegate.version
		set(value) {
			delegate.version = value
		}

	override val maximumSize: Long
		get() = delegate.maximumSize

	override fun setMaximumSize(numBytes: Long): Long = delegate.setMaximumSize(numBytes)

	override var pageSize: Long
		get() = delegate.pageSize
		set(value) {
			delegate.pageSize = value
		}

	override fun query(query: String): Cursor = query(SimpleSQLiteQuery(query))

	override fun query(query: String, bindArgs: Array<out Any?>): Cursor =
		query(SimpleSQLiteQuery(query, bindArgs))

	override fun query(query: SupportSQLiteQuery): Cursor = queryInternal(query, null)

	override fun query(
		query: SupportSQLiteQuery,
		cancellationSignal: CancellationSignal?,
	): Cursor = queryInternal(query, cancellationSignal)

	override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long =
		delegate.insertWithOnConflict(table, null, values, conflictAlgorithm)

	override fun delete(
		table: String,
		whereClause: String?,
		whereArgs: Array<out Any?>?,
	): Int {
		val sql = buildString {
			append("DELETE FROM ")
			append(table)
			if (!whereClause.isNullOrEmpty()) {
				append(" WHERE ")
				append(whereClause)
			}
		}
		return compileStatement(sql).use { statement ->
			SimpleSQLiteQuery.bind(statement, whereArgs)
			statement.executeUpdateDelete()
		}
	}

	override fun update(
		table: String,
		conflictAlgorithm: Int,
		values: ContentValues,
		whereClause: String?,
		whereArgs: Array<out Any?>?,
	): Int {
		require(values.size() != 0) { "Empty values" }

		val setValuesSize = values.size()
		val bindArgsSize = if (whereArgs == null) setValuesSize else setValuesSize + whereArgs.size
		val bindArgs = arrayOfNulls<Any>(bindArgsSize)
		val sql = buildString {
			append("UPDATE ")
			append(CONFLICT_VALUES[conflictAlgorithm])
			append(table)
			append(" SET ")

			var index = 0
			for (columnName in values.keySet()) {
				append(if (index > 0) "," else "")
				append(columnName)
				append("=?")
				bindArgs[index] = values[columnName]
				index++
			}
			if (whereArgs != null) {
				whereArgs.forEach { argument ->
					bindArgs[index] = argument
					index++
				}
			}
			if (!TextUtils.isEmpty(whereClause)) {
				append(" WHERE ")
				append(whereClause)
			}
		}

		return compileStatement(sql).use { statement ->
			SimpleSQLiteQuery.bind(statement, bindArgs)
			statement.executeUpdateDelete()
		}
	}

	override fun execSQL(sql: String) {
		delegate.execSQL(sql)
	}

	override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
		delegate.execSQL(sql, bindArgs)
	}

	override val isReadOnly: Boolean
		get() = delegate.isReadOnly

	override val isOpen: Boolean
		get() = delegate.isOpen

	override fun needUpgrade(newVersion: Int): Boolean = delegate.needUpgrade(newVersion)

	override val path: String?
		get() = delegate.path

	override fun setLocale(locale: Locale) {
		delegate.setLocale(locale)
	}

	override fun setMaxSqlCacheSize(cacheSize: Int) {
		delegate.setMaxSqlCacheSize(cacheSize)
	}

	override fun setForeignKeyConstraintsEnabled(enabled: Boolean) {
		delegate.setForeignKeyConstraintsEnabled(enabled)
	}

	override fun enableWriteAheadLogging(): Boolean = delegate.enableWriteAheadLogging()

	override fun disableWriteAheadLogging() {
		delegate.disableWriteAheadLogging()
	}

	override val isWriteAheadLoggingEnabled: Boolean
		get() = delegate.isWriteAheadLoggingEnabled

	override val attachedDbs: List<Pair<String, String>>?
		get() = delegate.attachedDbs

	override val isDatabaseIntegrityOk: Boolean
		get() = delegate.isDatabaseIntegrityOk

	override fun close() {
		delegate.close()
	}

	internal fun isDelegate(database: VendorSQLiteDatabase): Boolean = delegate === database

	private fun queryInternal(
		query: SupportSQLiteQuery,
		cancellationSignal: CancellationSignal?,
	): Cursor {
		val cursorFactory = VendorSQLiteDatabase.CursorFactory { _, driver, editTable, sqliteQuery ->
			val vendorQuery = checkNotNull(sqliteQuery)
			query.bindTo(SQLiteXSupportSQLiteProgram(vendorQuery))
			VendorSQLiteCursor(driver, editTable, vendorQuery)
		}
		return if (cancellationSignal == null) {
			delegate.rawQueryWithFactory(cursorFactory, query.sql, EMPTY_STRING_ARRAY, null)
		} else {
			delegate.rawQueryWithFactory(
				cursorFactory,
				query.sql,
				EMPTY_STRING_ARRAY,
				null,
				cancellationSignal,
			)
		}
	}

	private class TransactionListenerAdapter(
		private val listener: SQLiteTransactionListener,
	) : VendorSQLiteTransactionListener {
		override fun onBegin() {
			listener.onBegin()
		}

		override fun onCommit() {
			listener.onCommit()
		}

		override fun onRollback() {
			listener.onRollback()
		}
	}

	private companion object {
		private val CONFLICT_VALUES =
			arrayOf("", " OR ROLLBACK ", " OR ABORT ", " OR FAIL ", " OR IGNORE ", " OR REPLACE ")
		private val EMPTY_STRING_ARRAY = emptyArray<String>()
	}
}
