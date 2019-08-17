package com.adsamcik.tracker.import.file

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.common.database.AppDatabase
import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.File

//todo UNIQUE constraint can fail the import -> using autoincrement could break foreign keys
class DatabaseImport : FileImport {
	override val supportedExtensions: Collection<String> = listOf("db")

	override fun import(database: AppDatabase, file: File) {
		val fromDatabase = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
		importDatabase(fromDatabase, database.openHelper.writableDatabase)
	}

	private fun SupportSQLiteDatabase.getColumns(tableName: String): Array<String> {
		query("SELECT * FROM $tableName").use {
			return it.columnNames
		}
	}

	private fun addIfColumnIsRequired(sql: String, requiredColumns: MutableList<String>) {
		sql.substringAfter('(').split(',').forEach { columnDefinition ->
			if (columnDefinition.startsWith('`') && columnDefinition.contains("NOT NULL")) {
				val columnName = columnDefinition.substringAfter('`').substringBefore('`')
				requiredColumns.add(columnName)
			}
		}
	}

	private fun SupportSQLiteDatabase.getRequiredColumns(tableName: String): List<String> {
		query("SELECT name, sql FROM sqlite_master WHERE type='table' and name == ? ORDER BY name",
				arrayOf(tableName)).use {
			return if (it.moveToNext()) {
				val requiredColumns = mutableListOf<String>()
				val sql = it.getString(1)

				addIfColumnIsRequired(sql, requiredColumns)

				requiredColumns
			} else {
				emptyList()
			}
		}
	}

	private fun SupportSQLiteDatabase.getAllTables(): List<String> {
		query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use {
			val list = mutableListOf<String>()
			while (it.moveToNext()) {
				list.add(it.getString(0))
			}

			return list
		}
	}

	private fun getMatchingColumns(fromDatabase: SupportSQLiteDatabase,
	                               toDatabase: SupportSQLiteDatabase,
	                               table: String): List<String> {
		val fromColumns = fromDatabase.getColumns(table)
		val toColumns = toDatabase.getColumns(table)
		return fromColumns.toList().filter { toColumns.contains(it) }
	}

	private fun getMatchingTables(fromDatabase: SupportSQLiteDatabase,
	                              toDatabase: SupportSQLiteDatabase): List<String> {
		val fromTables = fromDatabase.getAllTables()
		val toTables = toDatabase.getAllTables()
		return fromTables.toList().filter { toTables.contains(it) }
	}

	private fun importRow(to: SupportSQLiteDatabase, row: Cursor, columnsJoined: String, tableName: String) {
		val values = mutableListOf<String>()

		for (i in 0 until row.columnCount) {
			values.add(row.getString(i))
		}

		val valuesQuery = values.joinToString(separator = ",", transform = { "'$it'" })
		to.execSQL("INSERT INTO $tableName ($columnsJoined) VALUES ($valuesQuery)")
	}

	private fun importTable(from: SupportSQLiteDatabase, to: SupportSQLiteDatabase, tableName: String) {
		val matchingColumns = getMatchingColumns(from, to, tableName)

		from.query("SELECT ${matchingColumns.joinToString(separator = ",")} FROM $tableName").use {
			val columnsJoined = it.columnNames.joinToString(separator = ",")
			while (it.moveToNext()) {
				importRow(to, it, columnsJoined, tableName)
			}
		}

	}

	private fun importDatabase(from: SupportSQLiteDatabase, to: SupportSQLiteDatabase) {
		getMatchingTables(from, to).forEach { tableName ->
			val requiredColumns = from.getRequiredColumns(tableName)
			val columns = to.getColumns(tableName)

			if (columns.toList().containsAll(requiredColumns)) {
				importTable(from, to, tableName)
			}
		}
	}
}
