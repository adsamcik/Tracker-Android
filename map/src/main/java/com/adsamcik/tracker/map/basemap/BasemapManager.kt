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

    /**
     * Returns the filesystem path for the default basemap if already extracted, or null.
     */
    fun defaultBasemapPath(): String? {
        val file = File(basemapDir, BUNDLED_BASEMAP_FILENAME)
        return if (file.exists()) file.absolutePath else null
    }

    /**
     * Ensure the bundled basemap asset is available on the filesystem.
     * PMTiles requires random-access I/O which Android's AssetManager
     * cannot provide, so we copy to internal storage on first use.
     */
    suspend fun ensureDefaultBasemap(): String = withContext(Dispatchers.IO) {
        basemapDir.mkdirs()
        val target = File(basemapDir, BUNDLED_BASEMAP_FILENAME)
        if (needsBundledBasemapCopy(target)) {
            context.assets.open(ASSET_BASEMAP_NAME).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
        }
        target.absolutePath
    }

    companion object {
        private val PMTILES_MAGIC = "PMTiles".encodeToByteArray()
        private const val PMTILES_VERSION = 0x03
        private const val PMTILES_HEADER_PREFIX_LENGTH = 8
        private const val BUNDLED_BASEMAP_FILENAME = "default.pmtiles"
        private const val ASSET_BASEMAP_NAME = "basemap.pmtiles"
    }

    private fun needsBundledBasemapCopy(target: File): Boolean {
        if (!target.exists() || target.length() < PMTILES_HEADER_PREFIX_LENGTH) {
            return true
        }

        return !target.inputStream().use { input ->
            val header = ByteArray(PMTILES_HEADER_PREFIX_LENGTH)
            val bytesRead = input.read(header)
            bytesRead == PMTILES_HEADER_PREFIX_LENGTH &&
                header.copyOfRange(0, PMTILES_MAGIC.size).contentEquals(PMTILES_MAGIC) &&
                header[PMTILES_MAGIC.size].toInt() == PMTILES_VERSION
        }
    }
}
