package com.adsamcik.tracker.impexp.backup

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Imports rows into the existing Room instance; no database files or connections are replaced. */
object LogicalDataBackupImporter {
    suspend fun normalizeOwnedCopy(context: Context, source: File) {
        // This is a disposable, app-owned copy, never the user's file or the live database.
        val database = AppDatabase.fileBuilder(context, source.absolutePath).build()
        try {
            database.openHelper.writableDatabase
            database.sourceEvidenceStateDao().ensure()
        } finally { database.close() }
    }

    fun validateOwnedCopy(source: File, target: SupportSQLiteDatabase, tracker: Boolean) {
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { backup ->
            backup.rawQuery("PRAGMA quick_check", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != "ok") throw IOException("Backup database is damaged")
            }
            backup.rawQuery("PRAGMA foreign_key_check", null).use { if (it.moveToFirst()) throw IOException("Backup data has broken references") }
            val excluded = if (tracker) TRACKER_RUNTIME_TABLES else emptySet()
            val installed = target.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use { cursor ->
                buildSet { while (cursor.moveToNext()) if (cursor.getString(0) !in INTERNAL_TABLES && cursor.getString(0) !in excluded) add(cursor.getString(0)) }
            }
            val archived = backup.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) if (cursor.getString(0) !in INTERNAL_TABLES && cursor.getString(0) !in excluded) add(cursor.getString(0)) }
            }
            if (installed != archived) throw IOException("Backup data tables differ from the installed schema")
            installed.forEach { table ->
                val columns = target.query("PRAGMA table_info(${quote(table)})").use { cursor ->
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
                }
                backup.rawQuery("SELECT * FROM ${quote(table)} LIMIT 0", null).use {
                    if (it.columnNames.toSet() != columns) throw IOException("Backup data columns differ from the installed schema")
                }
            }
        }
    }

    suspend fun replaceTrackerRows(
        database: AppDatabase, source: File, freshEpoch: Long, nowMs: Long,
        sourcePolicies: SourcePolicyRepository,
    ) {
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { backup ->
            val savedState = backup.rawQuery("SELECT * FROM source_evidence_state WHERE id=1", null).use { cursor ->
                if (!cursor.moveToFirst()) throw IOException("Backup state is missing")
                SavedState(cursor.getLong(cursor.getColumnIndexOrThrow("collected_data_epoch")),
                    cursor.getColumnIndex("restored_data_epochs").takeIf { it >= 0 }?.let(cursor::getString).orEmpty(),
                    cursor.getColumnIndexOrThrow("retained_from_ms").let { if (cursor.isNull(it)) null else cursor.getLong(it) },
                    cursor.getLong(cursor.getColumnIndexOrThrow("deleted_source_event_high_water_ordinal")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("revision")), cursor.getLong(cursor.getColumnIndexOrThrow("updated_at_ms")))
            }
            val originals = (savedState.aliases.split(',').filter(String::isNotBlank).map { value ->
                value.toLongOrNull()?.takeIf { it >= 0L } ?: throw IOException("Backup dataset identity is invalid")
            } + savedState.epoch).distinct().sorted()
            require(savedState.epoch >= 0L && freshEpoch > originals.last()) { "Restore epoch must be newer than all retained recording epochs" }
            database.withTransaction {
                val target = database.openHelper.writableDatabase
                target.query("SELECT collected_data_epoch FROM source_evidence_state WHERE id=1").use { cursor ->
                    require(cursor.moveToFirst() && freshEpoch >= cursor.getLong(0))
                }
                val localChoices = (sourcePolicies.currentState() as? SourcePolicyAuthorityState.Active)?.snapshot
                    ?: throw IOException("Current tracking choices are unavailable")
                val localFingerprint = database.sourcePolicyDao().authority()?.legacySettingsFingerprint
                val localConsentEpoch = database.sourcePolicyDao().maximumConsentEpoch() ?: -1L
                replaceRows(backup, target, excludedTables = TRACKER_RUNTIME_TABLES)
                val existingRevision = target.query("SELECT revision FROM source_evidence_state WHERE id=1").use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                }
                target.execSQL("UPDATE source_evidence_state SET collected_data_epoch=?, restored_data_epochs=?, " +
                    "retained_from_ms=?, deleted_source_event_high_water_ordinal=?, revision=?, updated_at_ms=? WHERE id=1",
                    arrayOf<Any?>(freshEpoch, originals.joinToString(","), savedState.floor, savedState.highWater,
                        Math.addExact(maxOf(existingRevision, savedState.revision), 1L), maxOf(nowMs, savedState.updatedAt)))
                // Restored lifecycle state is history, never a request to restart the old run.
                // Keep unknown end times unknown; do not invent a measured session duration.
                target.execSQL("UPDATE logical_tracking_session SET state='FAILED', failure_code='RESTORED_INTERRUPTED_SESSION' " +
                    "WHERE state NOT IN ('FINALIZED','CLOSED','FAILED')")
                target.execSQL("UPDATE source_service_run SET state='FAILED', completion_reason='RESTORED_INTERRUPTED_SESSION', " +
                    "runtime_acknowledgement='FAILED' WHERE state NOT IN ('FINALIZED','CLOSED','FAILED')")
                target.execSQL("UPDATE lifecycle_desired_action SET status='SUPERSEDED', failure_code='DATA_RESTORED' " +
                    "WHERE status IN ('AWAITING_FOREGROUND','PENDING','APPLYING','CLEANUP_REQUIRED','TEMPORARILY_ILLEGAL')")
                target.execSQL("UPDATE activity_automatic_start_action SET status='TERMINAL', terminal_at_ms=?, terminal_reason='DATA_RESTORED' " +
                    "WHERE status IN ('RESERVED','RETRYABLE','START_REQUESTED')", arrayOf(nowMs))
                // Publish local settings in the same transaction, so Room observers can never
                // project an archived choice into the current settings or their Proto mirror.
                sourcePolicies.reissueAfterDataRestore(localChoices, localFingerprint, localConsentEpoch)
                checkForeignKeys(target)
            }
            database.invalidationTracker.refreshAsync()
        }
    }

    suspend fun replaceAuxiliaryRows(source: File, target: SupportSQLiteDatabase) {
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { backup ->
            target.beginTransaction()
            try {
                replaceRows(backup, target, emptySet())
                checkForeignKeys(target)
                target.setTransactionSuccessful()
            } finally { target.endTransaction() }
        }
    }

    fun backedUpRetentionFloor(source: File): Long? = SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { backup ->
        backup.rawQuery("SELECT retained_from_ms FROM source_evidence_state WHERE id=1", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getLong(0)
        }
    }

    fun maximumOriginalEpoch(source: File): Long = SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { backup ->
        backup.rawQuery("SELECT collected_data_epoch,restored_data_epochs FROM source_evidence_state WHERE id=1", null).use { cursor ->
            require(cursor.moveToFirst())
            val current = cursor.getLong(0).also { require(it >= 0L) }
            (cursor.getString(1).split(',').filter(String::isNotBlank).map { value ->
                requireNotNull(value.toLongOrNull()).also { require(it >= 0L) }
            } + current).max()
        }
    }

    private suspend fun replaceRows(source: SQLiteDatabase, target: SupportSQLiteDatabase, excludedTables: Set<String>) {
        val sourceTables = source.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val tables = target.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use { cursor ->
            buildList { while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                if (name !in INTERNAL_TABLES && name !in excludedTables) add(name)
            } }
        }
        if (tables.any { it !in sourceTables }) throw IOException("Backup is missing saved-data tables")
        if (sourceTables.any { !it.startsWith("sqlite_") && it !in tables && it !in INTERNAL_TABLES && it !in excludedTables }) {
            throw IOException("Backup contains data the installed schema cannot import")
        }
        // Only installed triggers are restored. No SQL definition from a backup is executed.
        val triggers = target.query("SELECT name,sql FROM sqlite_master WHERE type='trigger'").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
        }
        target.execSQL("PRAGMA defer_foreign_keys=ON")
        triggers.forEach { target.execSQL("DROP TRIGGER ${quote(it.first)}") }
        tables.forEach { target.execSQL("DELETE FROM ${quote(it)}") }
        for (table in tables) {
            currentCoroutineContext().ensureActive()
            val targetColumns = target.query("PRAGMA table_info(${quote(table)})").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            source.rawQuery("SELECT * FROM ${quote(table)}", null).use { rows ->
                if (rows.columnNames.toSet() != targetColumns.toSet()) throw IOException("Backup columns differ from installed data schema")
                val indexes = targetColumns.map(rows::getColumnIndexOrThrow)
                target.compileStatement("INSERT INTO ${quote(table)} (${targetColumns.joinToString(",", transform = ::quote)}) " +
                    "VALUES (${targetColumns.joinToString(",") { "?" }})").use { statement ->
                    while (rows.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        statement.clearBindings()
                        indexes.forEachIndexed { binding, index ->
                            when (rows.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> statement.bindNull(binding + 1)
                                Cursor.FIELD_TYPE_INTEGER -> statement.bindLong(binding + 1, rows.getLong(index))
                                Cursor.FIELD_TYPE_FLOAT -> statement.bindDouble(binding + 1, rows.getDouble(index))
                                Cursor.FIELD_TYPE_BLOB -> statement.bindBlob(binding + 1, rows.getBlob(index))
                                else -> statement.bindString(binding + 1, rows.getString(index))
                            }
                        }
                        statement.executeInsert()
                    }
                }
            }
        }
        val hasSequence = sourceTables.contains("sqlite_sequence")
        if (hasSequence) {
            source.rawQuery("SELECT name,seq FROM sqlite_sequence", null).use { rows ->
                while (rows.moveToNext()) if (rows.getString(0) in tables) {
                    target.execSQL("DELETE FROM sqlite_sequence WHERE name=?", arrayOf(rows.getString(0)))
                    target.execSQL("INSERT INTO sqlite_sequence(name,seq) VALUES(?,?)", arrayOf(rows.getString(0), rows.getLong(1)))
                }
            }
        }
        triggers.forEach { (_, sql) -> target.execSQL(sql) }
    }

    private fun checkForeignKeys(database: SupportSQLiteDatabase) {
        database.query("PRAGMA foreign_key_check").use { if (it.moveToFirst()) throw IOException("Backup data has broken references") }
    }

    private fun quote(value: String): String = "\"${value.replace("\"", "\"\"")}\""
    private val INTERNAL_TABLES = setOf("room_master_table", "android_metadata")
    private val TRACKER_RUNTIME_TABLES = setOf("source_evidence_state", "collected_data_deletion_operation", "retention_work_execution_receipt")
    private data class SavedState(val epoch: Long, val aliases: String, val floor: Long?, val highWater: Long,
        val revision: Long, val updatedAt: Long)
}
