package com.adsamcik.tracker.impexp.backup

import android.content.Context
import com.adsamcik.tracker.impexp.exporter.DatabaseBackupArchive
import com.adsamcik.tracker.impexp.exporter.DatabaseBackupScope
import com.adsamcik.tracker.impexp.exporter.SQLiteDatabaseCopyLockProvider
import java.io.OutputStream

/** The restore coordinator preserves the replaced dataset with the same data-only writer. */
object DataBackupSnapshot {
    suspend fun write(context: Context, output: OutputStream) = DatabaseBackupArchive.writeData(
        DatabaseBackupScope.discover(context), output, SQLiteDatabaseCopyLockProvider)
}
