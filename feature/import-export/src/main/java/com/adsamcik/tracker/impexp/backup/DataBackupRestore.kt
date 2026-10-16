package com.adsamcik.tracker.impexp.backup

import android.net.Uri

/** A data backup replaces saved data only after the caller obtains the user's confirmation. */
interface DataBackupRestore {
    /** Stages and validates the selected archive for confirmation without changing recorded data. */
    suspend fun inspect(uri: Uri): DataBackupInspection
    /** Releases the app-owned staged copy when the preview is abandoned. */
    suspend fun discardPreview(token: String)
    /**
     * Reimports the confirmed preview into existing data stores; settings are preserved and tracking remains
     * stopped.
     */
    suspend fun restore(preview: DataBackupPreview): DataBackupRestoreResult
    /** Completes an interrupted confirmed import before storage readers and writers reopen. */
    suspend fun reconcilePendingRestore(): Boolean
    /** Clear all cancels and joins a confirmed restore before removing its owned files. */
    suspend fun cancelAndClearOwnedData() {}
}

/** Opaque staged-backup token and its original data epoch; -1 means the epoch has not yet been bound. */
data class DataBackupPreview(val token: String, val originalDataEpoch: Long = -1L)

/** Distinguishes another file format, an invalid backup and a validated preview requiring confirmation. */
sealed interface DataBackupInspection {
    /** The input is not a recognized Tracker data backup. */
    data object NotBackup : DataBackupInspection
    /** The archive could not be read or validated as a complete supported backup. */
    data object Invalid : DataBackupInspection
    /** The archive is staged and validated; no live data has been replaced. */
    data class Ready(val preview: DataBackupPreview) : DataBackupInspection
}

/** Whether confirmed logical data replacement completed or remains unavailable. */
enum class DataBackupRestoreResult { Restored, Unavailable }
