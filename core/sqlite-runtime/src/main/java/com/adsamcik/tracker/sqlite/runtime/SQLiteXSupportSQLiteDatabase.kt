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
import org.sqlite.database.sqlite.SQLiteReadOnlyTransactionBridge
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
		sqliteXCall { SQLiteXSupportSQLiteStatement(delegate.compileStatement(sql)) }

	override fun beginTransaction() {
		sqliteXCall { delegate.beginTransaction() }
	}

	override fun beginTransactionNonExclusive() {
		sqliteXCall { delegate.beginTransactionNonExclusive() }
	}

	/** Uses the pinned binding's deferred session mode and read-only pooled connection. */
	override fun beginTransactionReadOnly() {
		sqliteXCall { SQLiteReadOnlyTransactionBridge.begin(delegate, null) }
	}

	override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) {
		sqliteXCall { delegate.beginTransactionWithListener(TransactionListenerAdapter(transactionListener)) }
	}

	override fun beginTransactionWithListenerNonExclusive(
		transactionListener: SQLiteTransactionListener,
	) {
		sqliteXCall { delegate.beginTransactionWithListenerNonExclusive(TransactionListenerAdapter(transactionListener)) }
	}

	override fun beginTransactionWithListenerReadOnly(
		transactionListener: SQLiteTransactionListener,
	) {
		sqliteXCall { SQLiteReadOnlyTransactionBridge.begin(delegate, TransactionListenerAdapter(transactionListener)) }
	}

	override fun endTransaction() {
		sqliteXCall { delegate.endTransaction() }
	}

	override fun setTransactionSuccessful() {
		sqliteXCall { delegate.setTransactionSuccessful() }
	}

	override fun inTransaction(): Boolean = sqliteXCall { delegate.inTransaction() }

	override val isDbLockedByCurrentThread: Boolean
		get() = sqliteXCall { delegate.isDbLockedByCurrentThread }

	override fun yieldIfContendedSafely(): Boolean = sqliteXCall { delegate.yieldIfContendedSafely() }

	override fun yieldIfContendedSafely(sleepAfterYieldDelayMillis: Long): Boolean =
		sqliteXCall { delegate.yieldIfContendedSafely(sleepAfterYieldDelayMillis) }

	override val isExecPerConnectionSQLSupported: Boolean
		get() = false

	override fun execPerConnectionSQL(sql: String, bindArgs: Array<out Any?>?) {
		throw UnsupportedOperationException(
			"The official SQLite Android binding does not expose per-connection SQL execution.",
		)
	}

	override var version: Int
		get() = sqliteXCall { delegate.version }
		set(value) {
			sqliteXCall { delegate.version = value }
		}

	override val maximumSize: Long
		get() = sqliteXCall { delegate.maximumSize }

	override fun setMaximumSize(numBytes: Long): Long = sqliteXCall { delegate.setMaximumSize(numBytes) }

	override var pageSize: Long
		get() = sqliteXCall { delegate.pageSize }
		set(value) {
			sqliteXCall { delegate.pageSize = value }
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
		sqliteXCall { delegate.insertWithOnConflict(table, null, values, conflictAlgorithm) }

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
		sqliteXCall { delegate.execSQL(sql) }
	}

	override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
		sqliteXCall { delegate.execSQL(sql, bindArgs) }
	}

	override val isReadOnly: Boolean
		get() = sqliteXCall { delegate.isReadOnly }

	override val isOpen: Boolean
		get() = sqliteXCall { delegate.isOpen }

	override fun needUpgrade(newVersion: Int): Boolean = sqliteXCall { delegate.needUpgrade(newVersion) }

	override val path: String?
		get() = sqliteXCall { delegate.path }

	override fun setLocale(locale: Locale) {
		sqliteXCall { delegate.setLocale(locale) }
	}

	override fun setMaxSqlCacheSize(cacheSize: Int) {
		sqliteXCall { delegate.setMaxSqlCacheSize(cacheSize) }
	}

	override fun setForeignKeyConstraintsEnabled(enabled: Boolean) {
		sqliteXCall { delegate.setForeignKeyConstraintsEnabled(enabled) }
	}

	override fun enableWriteAheadLogging(): Boolean = sqliteXCall { delegate.enableWriteAheadLogging() }

	override fun disableWriteAheadLogging() {
		sqliteXCall { delegate.disableWriteAheadLogging() }
	}

	override val isWriteAheadLoggingEnabled: Boolean
		get() = sqliteXCall { delegate.isWriteAheadLoggingEnabled }

	override val attachedDbs: List<Pair<String, String>>?
		get() = sqliteXCall { delegate.attachedDbs }

	override val isDatabaseIntegrityOk: Boolean
		get() = sqliteXCall { delegate.isDatabaseIntegrityOk }

	override fun close() {
		sqliteXCall { delegate.close() }
	}

	internal fun isDelegate(database: VendorSQLiteDatabase): Boolean = delegate === database

	private fun queryInternal(
		query: SupportSQLiteQuery,
		cancellationSignal: CancellationSignal?,
	): Cursor {
		val cursorFactory = VendorSQLiteDatabase.CursorFactory { _, driver, editTable, sqliteQuery ->
			val vendorQuery = checkNotNull(sqliteQuery)
			query.bindTo(SQLiteXSupportSQLiteProgram(vendorQuery))
			SQLiteXSupportSQLiteCursor(driver, editTable, vendorQuery)
		}
		return sqliteXCall {
			if (cancellationSignal == null) {
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
