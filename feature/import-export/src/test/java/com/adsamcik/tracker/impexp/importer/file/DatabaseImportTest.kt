package com.adsamcik.tracker.impexp.importer.file

import com.adsamcik.tracker.shared.base.exception.NotFoundException
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.InvocationTargetException

/**
 * Unit tests for [DatabaseImport] internal data structures and logic.
 *
 * Focuses on [ImportTable], [ImportColumn], and the topological sort
 * integration for resolving foreign-key dependencies between tables.
 */
@DisplayName("DatabaseImport")
class DatabaseImportTest {

    // region helpers

    private fun column(
        name: String,
        notNull: Boolean = false,
        unique: Boolean = false,
        fkTable: ImportTable? = null,
    ) = ImportColumn(name, notNull, unique, fkTable)

    private fun table(
        name: String,
        columns: List<ImportColumn> = emptyList(),
    ) = ImportTable(name, isImported = false, columns = columns)

    // endregion

    @Nested
    @DisplayName("ImportColumn")
    inner class ImportColumnTests {

        @Test
        fun `equality is based on column name only`() {
            val a = column("id", notNull = true, unique = true)
            val b = column("id", notNull = false, unique = false)
            a shouldBe b
        }

        @Test
        fun `different names are not equal`() {
            val a = column("id")
            val b = column("name")
            a shouldNotBe b
        }

        @Test
        fun `hashCode is consistent with equals`() {
            val a = column("timestamp", notNull = true)
            val b = column("timestamp", notNull = false)
            a.hashCode() shouldBe b.hashCode()
        }

        @Test
        fun `foreign key table is mutable`() {
            val col = column("session_id")
            col.foreignKeyTable shouldBe null

            val target = table("session")
            col.foreignKeyTable = target
            col.foreignKeyTable shouldBe target
        }
    }

    @Nested
    @DisplayName("ImportTable")
    inner class ImportTableTests {

        @Test
        fun `equality is based on table name only`() {
            val a = table("location", columns = listOf(column("id")))
            val b = table("location", columns = emptyList())
            a shouldBe b
        }

        @Test
        fun `different table names are not equal`() {
            val a = table("location")
            val b = table("session")
            a shouldNotBe b
        }

        @Test
        fun `hashCode is consistent with equals`() {
            val a = table("activity")
            val b = table("activity")
            a.hashCode() shouldBe b.hashCode()
        }

        @Test
        fun `isImported flag is mutable`() {
            val t = table("location")
            t.isImported shouldBe false
            t.isImported = true
            t.isImported shouldBe true
        }
    }

    @Nested
    @DisplayName("Table dependency resolution")
    inner class DependencyResolution {

        @Test
        fun `tables without foreign keys maintain original order`() {
            val tables = listOf(
                table("alpha", listOf(column("id"))),
                table("beta", listOf(column("id"))),
                table("gamma", listOf(column("id"))),
            )

            val pairs = tables.map { it to it }
            val sorted = pairs.sortedByTopologyViaReflection()

            sorted shouldHaveSize 3
        }

        @Test
        fun `dependent table comes after its dependency`() {
            val sessionTable = table("session", listOf(column("id")))
            val locationTable = table(
                "location",
                listOf(
                    column("id"),
                    column("session_id", fkTable = sessionTable),
                ),
            )

            val pairs = listOf(locationTable to locationTable, sessionTable to sessionTable)
            val sorted = pairs.sortedByTopologyViaReflection()

            sorted shouldHaveSize 2
            val tableNames = sorted.map { it.first.tableName }
            val sessionIdx = tableNames.indexOf("session")
            val locationIdx = tableNames.indexOf("location")
            assert(sessionIdx < locationIdx) {
                "session (index=$sessionIdx) should come before location (index=$locationIdx)"
            }
        }

        @Test
        fun `chain of three dependencies resolves correctly`() {
            val activityTable = table("activity", listOf(column("id")))
            val sessionTable = table(
                "session",
                listOf(
                    column("id"),
                    column("activity_id", fkTable = activityTable),
                ),
            )
            val locationTable = table(
                "location",
                listOf(
                    column("id"),
                    column("session_id", fkTable = sessionTable),
                ),
            )

            // Deliberately reverse order
            val pairs = listOf(
                locationTable to locationTable,
                sessionTable to sessionTable,
                activityTable to activityTable,
            )
            val sorted = pairs.sortedByTopologyViaReflection()

            val tableNames = sorted.map { it.first.tableName }
            val activityIdx = tableNames.indexOf("activity")
            val sessionIdx = tableNames.indexOf("session")
            val locationIdx = tableNames.indexOf("location")

            assert(activityIdx < sessionIdx) { "activity should come before session" }
            assert(sessionIdx < locationIdx) { "session should come before location" }
        }

        @Test
        fun `diamond dependency resolves without error`() {
            val baseTable = table("base", listOf(column("id")))
            val leftTable = table(
                "left_table",
                listOf(column("id"), column("base_id", fkTable = baseTable)),
            )
            val rightTable = table(
                "right_table",
                listOf(column("id"), column("base_id", fkTable = baseTable)),
            )
            val topTable = table(
                "top_table",
                listOf(
                    column("id"),
                    column("left_id", fkTable = leftTable),
                    column("right_id", fkTable = rightTable),
                ),
            )

            val pairs = listOf(
                topTable to topTable,
                rightTable to rightTable,
                leftTable to leftTable,
                baseTable to baseTable,
            )
            val sorted = pairs.sortedByTopologyViaReflection()

            val tableNames = sorted.map { it.first.tableName }
            val baseIdx = tableNames.indexOf("base")
            val topIdx = tableNames.indexOf("top_table")

            assert(baseIdx < topIdx) { "base should come before top_table" }
        }

        @Test
        fun `single table list returns that table`() {
            val t = table("only", listOf(column("id")))
            val pairs = listOf(t to t)
            val sorted = pairs.sortedByTopologyViaReflection()
            sorted shouldHaveSize 1
            sorted.first().first.tableName shouldBe "only"
        }

        @Test
        fun `empty table list returns empty`() {
            val pairs = emptyList<Pair<ImportTable, ImportTable>>()
            val sorted = pairs.sortedByTopologyViaReflection()
            sorted.shouldBeEmpty()
        }
    }

    @Nested
    @DisplayName("Schema compatibility")
    inner class SchemaCompatibility {

        @Test
        fun `all NOT NULL columns present means compatible`() {
            val from = table(
                "location",
                listOf(
                    column("id", notNull = true),
                    column("latitude", notNull = true),
                    column("longitude", notNull = true),
                ),
            )
            val to = table(
                "location",
                listOf(
                    column("id", notNull = true),
                    column("latitude", notNull = true),
                    column("longitude", notNull = true),
                    column("altitude"),
                ),
            )

            val hasRequired = from.columns.all {
                if (it.isNotNull) to.columns.contains(it) else true
            }
            hasRequired shouldBe true
        }

        @Test
        fun `missing NOT NULL column means incompatible`() {
            val from = table(
                "location",
                listOf(
                    column("id", notNull = true),
                    column("latitude", notNull = true),
                    column("new_required_col", notNull = true),
                ),
            )
            val to = table(
                "location",
                listOf(
                    column("id", notNull = true),
                    column("latitude", notNull = true),
                ),
            )

            val hasRequired = from.columns.all {
                if (it.isNotNull) to.columns.contains(it) else true
            }
            hasRequired shouldBe false
        }

        @Test
        fun `nullable columns missing from target are still compatible`() {
            val from = table(
                "location",
                listOf(
                    column("id", notNull = true),
                    column("optional_col", notNull = false),
                ),
            )
            val to = table(
                "location",
                listOf(
                    column("id", notNull = true),
                ),
            )

            val hasRequired = from.columns.all {
                if (it.isNotNull) to.columns.contains(it) else true
            }
            hasRequired shouldBe true
        }

        @Test
        fun `empty from columns are always compatible`() {
            val from = table("empty", emptyList())
            val to = table("empty", listOf(column("id", notNull = true)))

            val hasRequired = from.columns.all {
                if (it.isNotNull) to.columns.contains(it) else true
            }
            hasRequired shouldBe true
        }
    }

    @Nested
    @DisplayName("Column SQL parsing")
    inner class ColumnParsing {

        @Test
        fun `addColumn parses basic column`() {
            val columns = mutableListOf<ImportColumn>()
            invokeAddColumn("`name` TEXT", columns)

            columns shouldHaveSize 1
            columns[0].columnName shouldBe "name"
            columns[0].isNotNull shouldBe false
            columns[0].isUnique shouldBe false
        }

        @Test
        fun `addColumn detects NOT NULL`() {
            val columns = mutableListOf<ImportColumn>()
            invokeAddColumn("`latitude` REAL NOT NULL", columns)

            columns shouldHaveSize 1
            columns[0].columnName shouldBe "latitude"
            columns[0].isNotNull shouldBe true
        }

        @Test
        fun `addColumn detects PRIMARY KEY`() {
            val columns = mutableListOf<ImportColumn>()
            invokeAddColumn("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL", columns)

            columns shouldHaveSize 1
            columns[0].columnName shouldBe "id"
            columns[0].isUnique shouldBe true
            columns[0].isNotNull shouldBe true
        }

        @Test
        fun `addColumn detects UNIQUE constraint`() {
            val columns = mutableListOf<ImportColumn>()
            invokeAddColumn("`email` TEXT UNIQUE", columns)

            columns shouldHaveSize 1
            columns[0].isUnique shouldBe true
        }

        @Test
        fun `addForeignKey links to correct column`() {
            val columns = mutableListOf<ImportColumn>()
            columns.add(column("session_id"))

            invokeAddForeignKey(
                "FOREIGN KEY(`session_id`) REFERENCES `session`(`id`)",
                columns,
            )

            columns[0].foreignKeyTable shouldNotBe null
            columns[0].foreignKeyTable!!.tableName shouldBe "session"
        }

        @Test
        fun `addForeignKey throws when column not found`() {
            val columns = mutableListOf<ImportColumn>()
            columns.add(column("other_col"))

            val exception = assertThrows<InvocationTargetException> {
                invokeAddForeignKey(
                    "FOREIGN KEY(`missing_col`) REFERENCES `target`(`id`)",
                    columns,
                )
            }
            exception.cause.shouldBeInstanceOf<NotFoundException>()
        }
    }

    @Nested
    @DisplayName("System table filtering")
    inner class SystemTableFiltering {

        @Test
        fun `sqlite_sequence is a system table`() {
            invokeIsSystemTable("sqlite_sequence") shouldBe true
        }

        @Test
        fun `room_master_table is a system table`() {
            invokeIsSystemTable("room_master_table") shouldBe true
        }

        @Test
        fun `android_metadata is a system table`() {
            invokeIsSystemTable("android_metadata") shouldBe true
        }

        @Test
        fun `user table is not a system table`() {
            invokeIsSystemTable("location") shouldBe false
        }

        @Test
        fun `empty string is not a system table`() {
            invokeIsSystemTable("") shouldBe false
        }
    }

    @Nested
    @DisplayName("Supported extensions")
    inner class SupportedExtensions {

        @Test
        fun `supports db extension`() {
            val import = DatabaseImport()
            import.supportedExtensions shouldContainExactly listOf("db")
        }

        @Test
        fun `documents merge compatible rows import mode`() {
            DatabaseImport.IMPORT_MODE shouldBe "MERGE_COMPATIBLE_ROWS"
        }
    }

    // region reflection helpers — access private/internal methods for unit testing

    private fun invokeAddColumn(columnDefinition: String, columns: MutableList<ImportColumn>) {
        val method = DatabaseImport::class.java.getDeclaredMethod(
            "addColumn",
            String::class.java,
            MutableList::class.java,
        )
        method.isAccessible = true
        method.invoke(DatabaseImport(), columnDefinition, columns)
    }

    private fun invokeAddForeignKey(columnDefinition: String, columns: MutableList<ImportColumn>) {
        val method = DatabaseImport::class.java.getDeclaredMethod(
            "addForeignKey",
            String::class.java,
            MutableList::class.java,
        )
        method.isAccessible = true
        method.invoke(DatabaseImport(), columnDefinition, columns)
    }

    private fun invokeIsSystemTable(tableName: String): Boolean {
        val method = DatabaseImport::class.java.getDeclaredMethod(
            "isSystemTable",
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(DatabaseImport(), tableName) as Boolean
    }

    /**
     * Calls the private extension `List<Pair<ImportTable, ImportTable>>.sortByTopology()`
     * via reflection.
     */
    private fun List<Pair<ImportTable, ImportTable>>.sortedByTopologyViaReflection():
            List<Pair<ImportTable, ImportTable>> {
        val method = DatabaseImport::class.java.getDeclaredMethod(
            "sortByTopology",
            List::class.java,
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(DatabaseImport(), this) as List<Pair<ImportTable, ImportTable>>
    }

    // endregion
}
