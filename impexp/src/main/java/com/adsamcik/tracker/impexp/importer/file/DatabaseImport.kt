package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import androidx.core.database.getStringOrNull
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.exception.NotFoundException
import com.adsamcik.tracker.shared.base.extension.sortByVertexes
import com.adsamcik.tracker.shared.base.graph.Edge
import com.adsamcik.tracker.shared.base.graph.Graph
import com.adsamcik.tracker.shared.base.graph.Vertex
import com.adsamcik.tracker.shared.base.graph.topSort
import io.requery.android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Merges compatible rows from a database file into the current database.
 * 
 * Known limitations:
 * - UNIQUE constraint violations may fail import; autoincrement usage could break foreign keys
 * - Direct SQL copy approach; consider Room-based migration for schema version mismatches
 * 
 * This is not a full-device restore: existing rows are preserved, and incoming rows are inserted
 * table-by-table when the source schema is structurally compatible.
 */
internal class DatabaseImport : FileImport {
	override val supportedExtensions: Collection<String> = listOf("db")

	override suspend fun import(
			context: Context,
			database: AppDatabase,
			stream: FileImportStream
	): ImportResult {
		val databaseTmpFile = createImportTempFile(context)
		var fromDatabase: SQLiteDatabase? = null
		try {
			databaseTmpFile.outputStream().use {
				stream.copyTo(it)
			}
			fromDatabase = SQLiteDatabase.openDatabase(
					databaseTmpFile.path,
					null,
					SQLiteDatabase.OPEN_READONLY
			)
			var result = ImportResult.EMPTY
			database.runInTransaction {
				result = importDatabase(fromDatabase, database.openHelper.writableDatabase)
			}
			return result
		} finally {
			fromDatabase?.close()
			databaseTmpFile.delete()
		}
	}

	private fun createImportTempFile(context: Context): File {
		val importCacheDir = File(context.cacheDir, IMPORT_CACHE_DIR).apply { mkdirs() }
		return File.createTempFile("db-import-", ".db", importCacheDir)
	}

	private fun addColumn(columnDefinition: String, requiredColumns: MutableList<ImportColumn>) {
		val isNotNull = columnDefinition.contains("NOT NULL")
		val columnName = columnDefinition.substringAfter('`').substringBefore('`')
		val isUnique = columnDefinition.contains("PRIMARY KEY") ||
				columnDefinition.contains("UNIQUE")
		val column = ImportColumn(columnName, isNotNull, isUnique, null)
		requiredColumns.add(column)
	}

	private fun addForeignKey(
			columnDefinition: String,
			requiredColumns: MutableList<ImportColumn>
	) {
		val thisTableColumn = columnDefinition
				.substringAfter("FOREIGN KEY(`")
				.substringBefore("`")

		val targetTableName = columnDefinition
				.substringAfter("REFERENCES `")
				.substringBefore("`")

		val column = requiredColumns.find { it.columnName == thisTableColumn }
				?: throw NotFoundException(
						"Expected column with name $thisTableColumn but had only ${
							requiredColumns.joinToString(
									transform = { it.columnName })
						}"
				)

		column.foreignKeyTable = ImportTable(
				targetTableName,
				isImported = false,
				columns = emptyList()
		)
	}

	private fun addIfColumnIsRequired(sql: String, requiredColumns: MutableList<ImportColumn>) {
		sql.substringAfter('(').split(',').forEach { split ->
			val columnDefinition = split.trim()
			if (columnDefinition.startsWith('`')) {
				addColumn(columnDefinition, requiredColumns)
			} else if (columnDefinition.startsWith("FOREIGN KEY")) {
				addForeignKey(columnDefinition, requiredColumns)
			}
		}
	}

	private fun SupportSQLiteDatabase.getColumns(tableName: String): List<ImportColumn> {
		query(
				"SELECT name, sql FROM sqlite_master WHERE type='table' and name == ? ORDER BY name",
				arrayOf(tableName)
		).use {
			return if (it.moveToNext()) {
				val requiredColumns = mutableListOf<ImportColumn>()
				val sql = it.getString(1)

				addIfColumnIsRequired(sql, requiredColumns)

				requiredColumns
			} else {
				throw NotFoundException("Could not find table with name $tableName")
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

	private fun getMatchingColumns(
			fromDatabase: SupportSQLiteDatabase,
			toDatabase: SupportSQLiteDatabase,
			table: String
	): List<ImportColumn> {
		val fromColumns = fromDatabase.getColumns(table)
		val toColumns = toDatabase.getColumns(table)
		return fromColumns.toList().filter { toColumns.contains(it) }
	}

	private val systemTables = arrayOf("sqlite_sequence", "room_master_table", "android_metadata")

	private fun isSystemTable(tableName: String): Boolean {
		return systemTables.contains(tableName)
	}

	private fun getMatchingTables(
			fromDatabase: SupportSQLiteDatabase,
			toDatabase: SupportSQLiteDatabase
	): List<String> {
		val fromTables = fromDatabase.getAllTables()
		val toTables = toDatabase.getAllTables()
		return fromTables.toList().filter { !isSystemTable(it) && toTables.contains(it) }
	}

	private enum class RowImportStatus { SUCCESS, SKIPPED, FAILED }

	private fun importRow(
			to: SupportSQLiteDatabase,
			row: Cursor,
			columnsJoined: String,
			tableName: String
	): RowImportStatus {
		val values = mutableListOf<String?>()

		for (i in 0 until row.columnCount) {
			values.add(row.getStringOrNull(i))
		}

		val valuesString = values.joinToString(separator = ", ", transform = { "?" })
		return try {
			to.execSQL(
					"INSERT INTO $tableName ($columnsJoined) VALUES ($valuesString)",
					values.toTypedArray()
			)
			RowImportStatus.SUCCESS
		} catch (e: SQLiteConstraintException) {
			if (tableName == "activity") {
				RowImportStatus.SKIPPED
			} else {
				Reporter.report(
						Exception(
								"Constraint issue while importing table $tableName",
								e
						)
				)
				RowImportStatus.FAILED
			}
		}
	}

	private fun importTable(
			from: SupportSQLiteDatabase,
			to: SupportSQLiteDatabase,
			tableName: String
	): ImportResult {
		val matchingColumns = getMatchingColumns(from, to, tableName)
		var success = 0
		var skipped = 0
		var failed = 0
		val errors = mutableListOf<String>()

		from.query(
				"SELECT ${
					matchingColumns.joinToString(separator = ",",
					                             transform = { it.columnName })
				} FROM $tableName"
		).use {
			val columnsJoined = it.columnNames.joinToString(separator = ",")
			while (it.moveToNext()) {
				when (importRow(to, it, columnsJoined, tableName)) {
					RowImportStatus.SUCCESS -> success++
					RowImportStatus.SKIPPED -> skipped++
					RowImportStatus.FAILED -> {
						failed++
						errors.add("Constraint violation in table $tableName")
					}
				}
			}
		}

		return ImportResult(
				successCount = success,
				skippedCount = skipped,
				failedCount = failed,
				errors = errors
		)
	}

	private fun List<Pair<ImportTable, ImportTable>>.sortByTopology(): List<Pair<ImportTable, ImportTable>> {
		val vertexList = MutableList(size) { Vertex(it) }
		val edgeList = map { pair ->
			pair.first.columns
					//get foreign keys and ignore nulls
					.mapNotNull { column -> column.foreignKeyTable }
					//find index of the table in our array
					.map { table -> indexOfFirst { it.first == table } }
		}
				//get indexes
				.withIndex()
				//map dependencies to edges
				.flatMap { indexedList ->
					//If A depends on B the edge leads from B to A, because B needs to come before A
					indexedList.value.map { Edge(Vertex(it), Vertex(indexedList.index)) }
				}

		val topSorted = Graph(vertexList, edgeList).topSort()

		return sortByVertexes(topSorted)
	}

	private fun importDatabase(from: SupportSQLiteDatabase, to: SupportSQLiteDatabase): ImportResult {
		val sortedTables = getMatchingTables(from, to)
				.map { tableName ->
					val fromColumns = from.getColumns(tableName)
					val toColumns = to.getColumns(tableName)

					ImportTable(tableName, false, fromColumns) to ImportTable(
							tableName,
							false,
							toColumns
					)
				}
				.sortByTopology()

		var result = ImportResult.EMPTY
		sortedTables.forEach { pair ->
			val hasRequiredColumns =
					pair.first.columns.all {
						if (it.isNotNull) {
							pair.second.columns.contains(it)
						} else {
							true
						}
					}

			if (hasRequiredColumns) {
				result += importTable(from, to, pair.first.tableName)
			}
		}
		return result
	}

	internal companion object {
		const val IMPORT_MODE = "MERGE_COMPATIBLE_ROWS"
		private const val IMPORT_CACHE_DIR = "database-import"
	}
}

internal data class ImportTable(
		val tableName: String,
		var isImported: Boolean,
		val columns: List<ImportColumn>
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as ImportTable

		if (tableName != other.tableName) return false

		return true
	}

	override fun hashCode(): Int {
		return tableName.hashCode()
	}
}

internal data class ImportColumn(
		val columnName: String,
		val isNotNull: Boolean,
		val isUnique: Boolean,
		var foreignKeyTable: ImportTable? = null
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as ImportColumn

		if (columnName != other.columnName) return false

		return true
	}

	override fun hashCode(): Int {
		return columnName.hashCode()
	}
}
