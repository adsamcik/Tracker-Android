package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import com.adsamcik.tracker.shared.base.database.legacy.ACTIVE_DATABASE_NAME
import com.adsamcik.tracker.shared.base.database.legacy.LEGACY_DATABASE_NAME
import java.io.File
import java.io.IOException

/** Only durable user data. Preferences, schedules, diagnostics and caches are excluded. */
object DatabaseBackupScope {
    const val POINTS_DATABASE_NAME = "points_database"
    val allowedPaths: Set<String> = setOf(
        "databases/$ACTIVE_DATABASE_NAME", "databases/$POINTS_DATABASE_NAME",
        "databases/$LEGACY_DATABASE_NAME", "files/basemap/custom.pmtiles",
        "files/ski-data/ski_infrastructure.db",
        "files/data-backup/legacy-database",
    )

    fun discover(context: Context): List<DatabaseBackupSource> {
        val active = context.getDatabasePath(ACTIVE_DATABASE_NAME)
        if (!active.isFile) throw IOException("No saved Tracker data is available")
        return allowedPaths.sorted().mapNotNull { path ->
            if (path == "databases/$LEGACY_DATABASE_NAME" && File(context.filesDir, "data-backup/legacy-database").isFile) return@mapNotNull null
            val file = destination(context, path)
            if (!file.exists()) return@mapNotNull null
            val owner = if (path.startsWith("databases/")) context.getDatabasePath(ACTIVE_DATABASE_NAME).parentFile!!.canonicalFile
                else context.filesDir.canonicalFile
            if (!file.isFile || !file.canonicalFile.toPath().startsWith(owner.toPath())) throw IOException("Backup data file is unavailable")
            DatabaseBackupSource(path, file, path.startsWith("databases/"))
        }
    }

    fun destination(context: Context, path: String): File {
        require(path in allowedPaths)
        return if (path.startsWith("databases/")) context.getDatabasePath(path.removePrefix("databases/"))
        else File(context.filesDir, path.removePrefix("files/"))
    }
}

data class DatabaseBackupSource(val archivePath: String, val file: File, val requiresDatabaseLock: Boolean)
