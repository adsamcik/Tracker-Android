package com.adsamcik.tracker.statistics.presenter

import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailSelection
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.stats.api.repository.ActivitySelectionDeletion
import com.adsamcik.tracker.stats.api.repository.ActivitySelectionDeletionRequest
import com.adsamcik.tracker.stats.api.repository.ActivitySelectionDeletionResult
import com.adsamcik.tracker.stats.api.repository.DeleteCellHistory
import com.adsamcik.tracker.stats.api.repository.DeleteCellHistoryRequest
import com.adsamcik.tracker.stats.api.repository.DeleteCellHistoryResult
import com.adsamcik.tracker.stats.api.repository.DeleteImportedPressureEntry
import com.adsamcik.tracker.stats.api.repository.DeleteImportedPressureEntryRequest
import com.adsamcik.tracker.stats.api.repository.DeleteImportedPressureEntryResult
import com.adsamcik.tracker.stats.api.repository.DeleteSelectedWifiHistory
import com.adsamcik.tracker.stats.api.repository.DeleteSelectedWifiHistoryRequest
import com.adsamcik.tracker.stats.api.repository.DeleteSelectedWifiHistoryResult
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.PressureSessionDeletion
import com.adsamcik.tracker.stats.api.repository.PressureSessionDeletionResult
import com.adsamcik.tracker.stats.api.repository.StepsSessionDeletion
import com.adsamcik.tracker.stats.api.repository.StepsSessionDeletionResult
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryActionTarget
import javax.inject.Inject

/**
 * Outcome of an exact source detail action, distinguishing completed export/deletion from changed or
 * unavailable data.
 */
enum class SourceHistoryActionResult { EXPORTED, DELETED, CHANGED, UNAVAILABLE, FAILED }

/** Routes exact selections through existing source deletion owners. It does not delete by dates. */
class SourceHistoryDetailActions @Inject constructor(
    private val activity: ActivitySelectionDeletion,
    private val wifi: DeleteSelectedWifiHistory,
    private val cell: DeleteCellHistory,
    private val steps: StepsSessionDeletion,
    private val pressure: PressureSessionDeletion,
    private val importedPressure: DeleteImportedPressureEntry,
    private val presenter: SourceHistoryDetailPresenter,
    private val clock: Clock,
) {
    /** Revalidates the exact saved selection and delegates deletion to its source owner, preserving unrelated data. */
    suspend fun delete(selection: SourceHistoryDetailSelection): SourceHistoryActionResult {
        // Re-open the exact source selection first. Clear/restore or a changed scope cannot be
        // silently substituted with another recording before applying the source-owned action.
        val fresh = presenter.load(selection) as? SourceHistoryDetailState.Loaded
            ?: return SourceHistoryActionResult.CHANGED
        val epoch = selection.readSnapshot?.collectedDataEpoch ?: return SourceHistoryActionResult.UNAVAILABLE
        if (fresh.selection.readSnapshot?.collectedDataEpoch != epoch) {
            return SourceHistoryActionResult.CHANGED
        }
        val now = clock.currentTimeMillis()
        return deleteTarget(selection.actionTarget, epoch, now)
    }

    private suspend fun deleteTarget(
        target: TrackingHistoryActionTarget,
        epoch: Long,
        now: Long,
    ): SourceHistoryActionResult = when (target) {
        is TrackingHistoryActionTarget.Activity ->
            activity.delete(ActivitySelectionDeletionRequest(target.selection, now, epoch)).toActionResult()
        is TrackingHistoryActionTarget.Wifi ->
            wifi.delete(DeleteSelectedWifiHistoryRequest(target.selection, epoch, now)).toActionResult()
        is TrackingHistoryActionTarget.Cell ->
            cell.delete(DeleteCellHistoryRequest(target.selection, now, epoch)).toActionResult()
        is TrackingHistoryActionTarget.ImportedPressure ->
            importedPressure.delete(DeleteImportedPressureEntryRequest(
                target.identity, target.importRevision, epoch)).toActionResult()
        is TrackingHistoryActionTarget.CapturedSource -> deleteCapturedSource(target, epoch)
        else -> SourceHistoryActionResult.UNAVAILABLE
    }

    private suspend fun deleteCapturedSource(
        target: TrackingHistoryActionTarget.CapturedSource,
        epoch: Long,
    ): SourceHistoryActionResult {
        // Original members share one source-only recording; repeated members are harmless NotFound.
        for (id in target.segmentIds) {
            val accepted = if (target.source == HistorySource.STEPS) {
                when (steps.deleteSelectedSession(id, epoch)) {
                    StepsSessionDeletionResult.Deleted, StepsSessionDeletionResult.NotFound -> true
                    else -> false
                }
            } else {
                when (pressure.deleteSelectedSession(id, epoch)) {
                    PressureSessionDeletionResult.Deleted, PressureSessionDeletionResult.NotFound -> true
                    else -> false
                }
            }
            if (!accepted) {
                return SourceHistoryActionResult.UNAVAILABLE
            }
        }
        return SourceHistoryActionResult.DELETED
    }

    private fun ActivitySelectionDeletionResult.toActionResult() = when (this) {
        is ActivitySelectionDeletionResult.Deleted, is ActivitySelectionDeletionResult.AlreadyDeleted,
        ActivitySelectionDeletionResult.NotFound -> SourceHistoryActionResult.DELETED
        is ActivitySelectionDeletionResult.RetryableFailure -> SourceHistoryActionResult.FAILED
        else -> SourceHistoryActionResult.UNAVAILABLE
    }

    private fun DeleteSelectedWifiHistoryResult.toActionResult() = when (this) {
        is DeleteSelectedWifiHistoryResult.Deleted, is DeleteSelectedWifiHistoryResult.AlreadyDeleted,
        DeleteSelectedWifiHistoryResult.NotFound -> SourceHistoryActionResult.DELETED
        is DeleteSelectedWifiHistoryResult.RetryableFailure -> SourceHistoryActionResult.FAILED
        else -> SourceHistoryActionResult.UNAVAILABLE
    }

    private fun DeleteCellHistoryResult.toActionResult() = when (this) {
        is DeleteCellHistoryResult.Deleted, DeleteCellHistoryResult.AlreadyDeleted,
        DeleteCellHistoryResult.NotFound -> SourceHistoryActionResult.DELETED
        is DeleteCellHistoryResult.RetryableFailure -> SourceHistoryActionResult.FAILED
        else -> SourceHistoryActionResult.UNAVAILABLE
    }

    private fun DeleteImportedPressureEntryResult.toActionResult() = when (this) {
        DeleteImportedPressureEntryResult.Deleted, DeleteImportedPressureEntryResult.AlreadyDeleted,
        DeleteImportedPressureEntryResult.NotFound -> SourceHistoryActionResult.DELETED
        is DeleteImportedPressureEntryResult.StaleSelection -> SourceHistoryActionResult.CHANGED
        is DeleteImportedPressureEntryResult.RetryableFailure -> SourceHistoryActionResult.FAILED
        else -> SourceHistoryActionResult.UNAVAILABLE
    }
}
