package com.adsamcik.tracker.impexp

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.adsamcik.tracker.impexp.importer.archive.SessionBundleStager
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

class OwnedTransferCleanupException(val residualPath: String, cause: Throwable? = null) :
    IOException("Transfer cleanup remains pending at $residualPath", cause)

/** Private, disposable transfer copies. Call only after import and export writers are stopped. */
object OwnedTransferFiles {
    /** A failure retains the full-clear journal so startup can retry the exact owned paths. */
    fun clearForFullDeletion(context: Context) {
        clearDirectory(context, context.cacheDir, "sharable", revokeShareGrant = true)
        clearDirectory(context, context.filesDir, "sharable", revokeShareGrant = true)
        clearDirectory(context, context.cacheDir, "portable-export-spool")
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
}
