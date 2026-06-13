package com.adsamcik.tracker.map.basemap

import android.content.Context
import android.net.Uri
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages user-imported PMTiles basemap files.
 *
 * Import flow: SAF picker -> copy to internal storage -> validate magic + version -> return file path.
 * The same flow supports a future companion tile downloader app that exposes
 * files via FileProvider -- zero changes needed in Tracker.
 */
class BasemapManager(
    private val context: Context,
    private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
) {

    // Lazy so constructing BasemapManager does not touch context.filesDir (a disk read). The map
    // creates this in Compose `remember {}`, which runs on the main thread; eagerly resolving
    // filesDir there triggers a StrictMode DiskReadViolation. All real users of basemapDir run
    // inside IO coroutines (importBasemap / ensureDefaultBasemap), so deferring is safe.
    private val basemapDir by lazy { File(context.filesDir, "basemap") }

    /** Copy a user-selected PMTiles file to internal storage. */
    suspend fun importBasemap(uri: Uri): BasemapImportResult = withContext(dispatchers.io) {
        basemapDir.mkdirs()
        val target = File(basemapDir, "custom.pmtiles")

        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext BasemapImportResult.SourceOpenFailed(uri)
        } ?: return@withContext BasemapImportResult.SourceOpenFailed(uri)

        try {
            input.use { source ->
                target.outputStream().use { output ->
                    source.copyTo(output)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            target.delete()
            return@withContext BasemapImportResult.CopyFailed(e)
        }

        validatePmtilesHeader(target)?.let { reason ->
            target.delete()
            return@withContext BasemapImportResult.InvalidFormat(reason)
        }

        BasemapImportResult.Success(target.absolutePath)
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
    suspend fun ensureDefaultBasemap(): String = withContext(dispatchers.io) {
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
        return validatePmtilesHeader(target) != null
    }

    /**
     * Returns null if the file's PMTiles v3 header is well-formed,
     * or a short human-readable reason string if it is malformed.
     * Used both to detect when the bundled asset must be re-extracted
     * and to validate user-imported PMTiles files.
     */
    private fun validatePmtilesHeader(target: File): String? {
        if (!target.exists()) return "File missing after copy"
        if (target.length() < PMTILES_HEADER_PREFIX_LENGTH) return "File too short to be a PMTiles archive"
        return target.inputStream().use { input ->
            val header = ByteArray(PMTILES_HEADER_PREFIX_LENGTH)
            val bytesRead = input.read(header)
            when {
                bytesRead != PMTILES_HEADER_PREFIX_LENGTH ->
                    "Could not read PMTiles header"
                !header.copyOfRange(0, PMTILES_MAGIC.size).contentEquals(PMTILES_MAGIC) ->
                    "File is not a PMTiles archive (wrong magic bytes)"
                header[PMTILES_MAGIC.size].toInt() != PMTILES_VERSION ->
                    "Unsupported PMTiles version (expected v3)"
                else -> null
            }
        }
    }
}
