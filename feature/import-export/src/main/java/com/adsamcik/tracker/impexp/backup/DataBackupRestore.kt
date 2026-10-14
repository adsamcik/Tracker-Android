package com.adsamcik.tracker.impexp.backup

import android.net.Uri

/** A data backup replaces saved data only after the caller obtains the user's confirmation. */
interface DataBackupRestore {
    suspend fun inspect(uri: Uri): DataBackupInspection
    suspend fun discardPreview(token: String)
    suspend fun restore(preview: DataBackupPreview): DataBackupRestoreResult
    /** Completes an interrupted confirmed import before storage readers and writers reopen. */
    suspend fun reconcilePendingRestore(): Boolean
    /** Clear all cancels and joins a confirmed restore before removing its owned files. */
    suspend fun cancelAndClearOwnedData() {}
}

data class DataBackupPreview(val token: String, val originalDataEpoch: Long = -1L)

sealed interface DataBackupInspection {
    data object NotBackup : DataBackupInspection
    data object Invalid : DataBackupInspection
    data class Ready(val preview: DataBackupPreview) : DataBackupInspection
}

enum class DataBackupRestoreResult { Restored, Unavailable }
