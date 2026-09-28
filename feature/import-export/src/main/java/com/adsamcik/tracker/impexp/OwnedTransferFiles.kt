package com.adsamcik.tracker.impexp

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.adsamcik.tracker.impexp.exporter.automation.ExportDestination
import com.adsamcik.tracker.impexp.importer.archive.SessionBundleStager
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption

class OwnedTransferCleanupException(val residualPath: String, cause: Throwable? = null) :
    IOException("Transfer cleanup remains pending at $residualPath", cause)

/** Private, disposable transfer copies. Call only after import and export writers are stopped. */
object OwnedTransferFiles {
    private const val PRIVATE_SCHEDULED_EXPORT_DIRECTORY = "exports"

    /** New scheduled files always use the owned directory; old saved directory names are inert. */
    fun privateScheduledExportFile(
        context: Context,
        @Suppress("UNUSED_PARAMETER") destination: ExportDestination.PrivateStorage,
        fileName: String,
    ): File {
        require(fileName.isNotBlank() && fileName != "." && fileName != ".." &&
            '/' !in fileName && '\\' !in fileName
        ) { "Scheduled export file name must be a single name" }
        val root = context.filesDir.canonicalFile
        val directory = File(root, PRIVATE_SCHEDULED_EXPORT_DIRECTORY)
        require(directory.canonicalFile == directory.absoluteFile) {
            "Private scheduled export directory must remain app-owned"
        }
        return File(directory, fileName)
    }

    /** Reserve a distinct output atomically so two scheduled plans cannot overwrite each other. */
    fun reservePrivateScheduledExportFile(
        context: Context,
        destination: ExportDestination.PrivateStorage,
        fileName: String,
    ): File {
        val requested = privateScheduledExportFile(context, destination, fileName)
        val directory = requireNotNull(requested.parentFile)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create private export directory")
        }
        if (directory.canonicalFile != directory.absoluteFile) {
            throw IOException("Private export directory is no longer app-owned")
        }
        val extensionAt = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
        repeat(MAX_PRIVATE_FILE_NAME_ATTEMPTS) { attempt ->
            val candidate = if (attempt == 0) requested else File(
                directory,
                fileName.substring(0, extensionAt) + "-$attempt" + fileName.substring(extensionAt),
            )
            try {
                Files.createFile(candidate.toPath())
                return candidate
            } catch (_: FileAlreadyExistsException) {
                // Another export already owns that name; try a distinct suffix.
            }
        }
        throw IOException("No unique private export file name is available")
    }

    /** A failure retains the full-clear journal so startup can retry the exact owned paths. */
    fun clearForFullDeletion(context: Context) {
        clearDirectory(context, context.cacheDir, "sharable", revokeShareGrant = true)
        clearDirectory(context, context.filesDir, "sharable", revokeShareGrant = true)
        clearDirectory(context, context.cacheDir, "portable-export-spool")
        clearDirectory(context, context.filesDir, PRIVATE_SCHEDULED_EXPORT_DIRECTORY)
        clearDirectory(context, context.cacheDir, "database-import")
        clearDirectory(context, context.cacheDir, "zip-import")
        try {
            SessionBundleStager.clearOwnedStaging(context)
        } catch (failure: IOException) {
            throw OwnedTransferCleanupException(
                File(context.cacheDir, SessionBundleStager.CACHE_DIRECTORY).absolutePath,
                failure,
            )
        }
    }

    private fun clearDirectory(
        context: Context,
        root: File,
        name: String,
        revokeShareGrant: Boolean = false,
    ) {
        val intendedPath = File(root, name).absolutePath
        try {
            val canonicalRoot = root.canonicalFile
            val directory = File(canonicalRoot, name)
            if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return
            if (directory.canonicalFile != directory.absoluteFile || !directory.isDirectory) {
                throw OwnedTransferCleanupException(directory.absolutePath)
            }
            val children = directory.listFiles()
                ?: throw OwnedTransferCleanupException(directory.absolutePath)
            children.forEach { child ->
                if (child.canonicalFile.parentFile != directory ||
                    child.canonicalFile != child.absoluteFile || !child.isFile
                ) {
                    throw OwnedTransferCleanupException(child.absolutePath)
                }
                if (revokeShareGrant) {
                    try {
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", child,
                        )
                        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (failure: Exception) {
                        throw OwnedTransferCleanupException(child.absolutePath, failure)
                    }
                }
                if (!child.delete()) {
                    throw OwnedTransferCleanupException(child.absolutePath)
                }
            }
            if (!directory.delete()) {
                throw OwnedTransferCleanupException(directory.absolutePath)
            }
        } catch (failure: OwnedTransferCleanupException) {
            throw failure
        } catch (failure: Exception) {
            throw OwnedTransferCleanupException(intendedPath, failure)
        }
    }

    private const val MAX_PRIVATE_FILE_NAME_ATTEMPTS = 1_000
}
