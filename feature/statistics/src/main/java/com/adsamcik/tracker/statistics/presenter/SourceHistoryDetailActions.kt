package com.adsamcik.tracker.statistics.presenter

import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailSelection
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.stats.api.repository.*
import javax.inject.Inject

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
    suspend fun delete(selection: SourceHistoryDetailSelection): SourceHistoryActionResult {
        // Re-open the exact source selection first. Clear/restore or a changed scope cannot be
        // silently substituted with another recording before applying the source-owned action.
        val fresh = presenter.load(selection) as? SourceHistoryDetailState.Loaded
            ?: return SourceHistoryActionResult.CHANGED
        val epoch = selection.readSnapshot?.collectedDataEpoch ?: return SourceHistoryActionResult.UNAVAILABLE
        if (fresh.selection.readSnapshot?.collectedDataEpoch != epoch) return SourceHistoryActionResult.CHANGED
        val now = clock.currentTimeMillis()
        return when (val target = selection.actionTarget) {
            is TrackingHistoryActionTarget.Activity -> when (activity.delete(ActivitySelectionDeletionRequest(target.selection, now, epoch))) {
                is ActivitySelectionDeletionResult.Deleted, is ActivitySelectionDeletionResult.AlreadyDeleted,
                ActivitySelectionDeletionResult.NotFound -> SourceHistoryActionResult.DELETED
                is ActivitySelectionDeletionResult.RetryableFailure -> SourceHistoryActionResult.FAILED
                else -> SourceHistoryActionResult.UNAVAILABLE
            }
            is TrackingHistoryActionTarget.Wifi -> when (wifi.delete(DeleteSelectedWifiHistoryRequest(target.selection, epoch, now))) {
                is DeleteSelectedWifiHistoryResult.Deleted, is DeleteSelectedWifiHistoryResult.AlreadyDeleted,
                DeleteSelectedWifiHistoryResult.NotFound -> SourceHistoryActionResult.DELETED
                is DeleteSelectedWifiHistoryResult.RetryableFailure -> SourceHistoryActionResult.FAILED
                else -> SourceHistoryActionResult.UNAVAILABLE
            }
            is TrackingHistoryActionTarget.Cell -> when (cell.delete(DeleteCellHistoryRequest(target.selection, now, epoch))) {
                is DeleteCellHistoryResult.Deleted, DeleteCellHistoryResult.AlreadyDeleted,
                DeleteCellHistoryResult.NotFound -> SourceHistoryActionResult.DELETED
                is DeleteCellHistoryResult.RetryableFailure -> SourceHistoryActionResult.FAILED
                else -> SourceHistoryActionResult.UNAVAILABLE
            }
            is TrackingHistoryActionTarget.ImportedPressure -> when (importedPressure.delete(DeleteImportedPressureEntryRequest(
                target.identity, target.importRevision, epoch))) {
                DeleteImportedPressureEntryResult.Deleted, DeleteImportedPressureEntryResult.AlreadyDeleted,
                DeleteImportedPressureEntryResult.NotFound -> SourceHistoryActionResult.DELETED
                is DeleteImportedPressureEntryResult.StaleSelection -> SourceHistoryActionResult.CHANGED
                is DeleteImportedPressureEntryResult.RetryableFailure -> SourceHistoryActionResult.FAILED
                else -> SourceHistoryActionResult.UNAVAILABLE
            }
            is TrackingHistoryActionTarget.CapturedSource -> {
                // These original members all represent one source-only logical recording. Existing
                // deletion owners fence that whole group; a repeated member is harmless NotFound.
                for (id in target.segmentIds) {
                    val accepted = if (target.source == HistorySource.STEPS) when (steps.deleteSelectedSession(id, epoch)) {
                        StepsSessionDeletionResult.Deleted, StepsSessionDeletionResult.NotFound -> true
                        else -> false
                    } else when (pressure.deleteSelectedSession(id, epoch)) {
                        PressureSessionDeletionResult.Deleted, PressureSessionDeletionResult.NotFound -> true
                        else -> false
                    }
                    if (!accepted) return SourceHistoryActionResult.UNAVAILABLE
                }
                SourceHistoryActionResult.DELETED
            }
            else -> SourceHistoryActionResult.UNAVAILABLE
        }
    }
}
