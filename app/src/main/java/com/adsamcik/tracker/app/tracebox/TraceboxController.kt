package com.adsamcik.tracker.app.tracebox

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

enum class TraceboxAvailability { UNAVAILABLE, INITIALIZING, DISABLED, READY, DEGRADED, DELETING }
enum class TraceboxExportState { IDLE, PREVIEW_READY, APPROVED, SAVED, CANCELLED, FAILED }
enum class TraceboxOperationResult { NONE, PROFILE_UPDATED, DATA_DELETED, SAVE_COMPLETE, SAVE_PARTIAL, FAILED }

data class TraceboxPreview(
    val includedValues: Int,
    val includedBytes: Long,
    val sourceProcesses: Int,
    val rawArtifactsExcluded: Boolean,
    val digestSha256: ByteArray,
) {
    init {
        require(includedValues >= 0)
        require(includedBytes >= 0)
        require(sourceProcesses >= 0)
        require(digestSha256.size == 32)
    }
}

data class TraceboxUiState(
    val availability: TraceboxAvailability,
    val enabled: Boolean,
    val exportState: TraceboxExportState = TraceboxExportState.IDLE,
    val preview: TraceboxPreview? = null,
    val result: TraceboxOperationResult = TraceboxOperationResult.NONE,
)

/**
 * App-facing bounded control surface. It never accepts Tracker location, sensor, account,
 * identifier, user-entered text, or generic record values.
 */
interface TraceboxController {
    val state: StateFlow<TraceboxUiState>

    fun setEnabled(enabled: Boolean)
    fun prepareStandardPackage()
    fun approvalIntent(): Intent?
    fun acceptApprovalResult(result: Intent?)
    fun shareIntent(): Intent?
    fun createSaveIntent(): Intent?
    fun save(destination: Uri)
    fun cancelPackage()
    fun deleteAllTraceboxData()
}
