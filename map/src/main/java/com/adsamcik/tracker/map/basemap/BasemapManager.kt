package com.adsamcik.tracker.map.basemap

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Manages user-imported PMTiles basemap files.
 *
 * Import flow: SAF picker -> copy to internal storage -> return file path.
 * The same flow supports a future companion tile downloader app that exposes
 * files via FileProvider -- zero changes needed in Tracker.
 */
class BasemapManager(private val context: Context) {

    private val basemapDir = File(context.filesDir, "basemap")

    /** Copy a user-selected PMTiles file to internal storage. Returns the absolute path. */
    suspend fun importBasemap(uri: Uri): String = withContext(Dispatchers.IO) {
        basemapDir.mkdirs()
        val target = File(basemapDir, "custom.pmtiles")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("Cannot open URI: $uri")
        target.absolutePath
    }

    /** Delete the custom basemap and revert to bundled default. */
    fun clearCustomBasemap() {
        File(basemapDir, "custom.pmtiles").delete()
    }

    /** Get custom basemap path, or null if using default. */
    fun customBasemapPath(): String? {
        val file = File(basemapDir, "custom.pmtiles")
        return if (file.exists()) file.absolutePath else null
    }
}
