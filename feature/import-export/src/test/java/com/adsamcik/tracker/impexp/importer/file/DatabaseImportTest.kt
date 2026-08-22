package com.adsamcik.tracker.impexp.importer.file

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DatabaseImport")
class DatabaseImportTest {

	@Test
	fun `supports database files and owns its transaction`() {
		val importer = DatabaseImport()

		importer.supportedExtensions shouldContainExactly listOf("db")
		importer.transactionMode shouldBe ImportTransactionMode.IMPORTER_MANAGED
		DatabaseImport.IMPORT_MODE shouldBe "READ_ONLY_COMPUTED_REMAP_TRANSACTION"
	}

	@Test
	fun `merge allowlist contains user facts but excludes runtime authority`() {
		DatabaseImport.USER_DATA_TABLES.contains("step_interval") shouldBe true
		DatabaseImport.USER_DATA_TABLES.contains("session_segment") shouldBe true
		DatabaseImport.USER_DATA_TABLES.contains("source_policy_authority") shouldBe false
		DatabaseImport.USER_DATA_TABLES.contains("source_demand") shouldBe false
		DatabaseImport.USER_DATA_TABLES.contains("logical_tracking_session") shouldBe false
		DatabaseImport.USER_DATA_TABLES.contains("source_event_wal") shouldBe false
	}

	@Test
	fun `table and column identity are schema-name based`() {
		ImportColumn("id", true, true) shouldBe ImportColumn("id", false, false)
		ImportTable("sample", listOf(ImportColumn("id", true, true))) shouldBe
			ImportTable("sample", emptyList())
	}

	@Test
	fun `new required target column without a default is incompatible`() {
		val source = ImportTable(
			tableName = "sample",
			columns = listOf(ImportColumn("id", true, true, type = "INTEGER")),
			isAutoIncrement = true,
		)
		val target = ImportTable(
			tableName = "sample",
			columns = listOf(
				ImportColumn("id", true, true, type = "INTEGER", primaryKeyPosition = 1),
				ImportColumn("required", true, false, type = "TEXT"),
			),
			isAutoIncrement = true,
		)

		target.canImport(source) shouldBe false
	}

	@Test
	fun `new required target column with a default remains compatible`() {
		val source = ImportTable(
			tableName = "sample",
			columns = listOf(ImportColumn("id", true, true, type = "INTEGER")),
			isAutoIncrement = true,
		)
		val target = ImportTable(
			tableName = "sample",
			columns = listOf(
				ImportColumn("id", true, true, type = "INTEGER", primaryKeyPosition = 1),
				ImportColumn("required", true, false, type = "TEXT", defaultValue = "''"),
			),
			isAutoIncrement = true,
		)

		target.canImport(source) shouldBe true
	}
}
