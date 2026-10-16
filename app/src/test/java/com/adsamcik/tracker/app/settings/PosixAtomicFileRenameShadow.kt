package com.adsamcik.tracker.app.settings

import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Android's replacing rename semantics for the Windows restore fixture; other methods call through. */
@Implements(value = AtomicFile::class, callThroughByDefault = true)
class PosixAtomicFileRenameShadow {
    companion object {
        var refusePreservedRestoreJournal = false

        @JvmStatic
        @Implementation
        fun rename(source: File, target: File) {
            if (target.isDirectory && !target.delete()) {
                Log.e("AtomicFile", "Failed to delete file which is a directory $target")
            }
            try {
                if (refusePreservedRestoreJournal && target.name == "confirmed.properties" &&
                    source.useLines { lines -> lines.any { it == "phase=preserved" } }) {
                    Log.e("AtomicFile", "Restore checkpoint rename refused by fixture")
                    return
                }
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (failure: IOException) {
                Log.e("AtomicFile", "Failed to rename $source to $target", failure)
            }
        }
    }
}
