package com.adsamcik.tracker.app.settings.tracebox

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.app.tracebox.TraceboxDeleteResult
import com.adsamcik.tracker.app.tracebox.TraceboxDiagnosticsOperations
import com.adsamcik.tracker.app.tracebox.TraceboxDiagnosticsState
import com.adsamcik.tracker.app.tracebox.TraceboxPackageCreationResult
import com.adsamcik.tracker.app.tracebox.TraceboxPackagePreparation
import com.adsamcik.tracker.app.tracebox.TraceboxPackageSaveResult
import com.adsamcik.tracker.app.tracebox.TraceboxPackageShareResult
import com.adsamcik.tracker.app.tracebox.TraceboxPolicyResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal sealed interface TraceboxUiEffect {
    data class Review(val intent: Intent) : TraceboxUiEffect
    data class Save(val intent: Intent) : TraceboxUiEffect
    data class Share(val intent: Intent) : TraceboxUiEffect
}

internal sealed interface TraceboxUiMessage {
    data object DiagnosticsEnabled : TraceboxUiMessage
    data object DiagnosticsDisabled : TraceboxUiMessage
    data object PolicyRestricted : TraceboxUiMessage
    data object PolicyPartial : TraceboxUiMessage
    data object OperationFailed : TraceboxUiMessage
    data object DeleteComplete : TraceboxUiMessage
    data object DeletePending : TraceboxUiMessage
    data object DeleteRejected : TraceboxUiMessage
    data class ReviewReady(val valueCount: Int, val bytes: Long) : TraceboxUiMessage
    data object PackageCreated : TraceboxUiMessage
    data object ApprovalCancelled : TraceboxUiMessage
    data object PackageRejected : TraceboxUiMessage
    data object PackageNotReady : TraceboxUiMessage
    data class SaveComplete(val bytes: Long) : TraceboxUiMessage
    data class SavePartial(val bytes: Long, val cancelled: Boolean) : TraceboxUiMessage
    data object SaveFailed : TraceboxUiMessage
    data object SaveCancelled : TraceboxUiMessage
    data object ShareChooserOpened : TraceboxUiMessage
    data object ShareDeliveryUnknown : TraceboxUiMessage
}

@HiltViewModel
class TraceboxDiagnosticsViewModel @Inject constructor(
    private val controller: TraceboxDiagnosticsOperations,
) : ViewModel() {
    val state: StateFlow<TraceboxDiagnosticsState> = controller.state

    private val mutableMessage =
        kotlinx.coroutines.flow.MutableStateFlow<TraceboxUiMessage?>(null)
    internal val message: StateFlow<TraceboxUiMessage?> = mutableMessage

    private val mutableEffects = Channel<TraceboxUiEffect>(Channel.BUFFERED)
    internal val effects = mutableEffects.receiveAsFlow()

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            mutableMessage.value = when (controller.setEnabled(enabled)) {
                TraceboxPolicyResult.COMPLETE -> {
                    if (enabled) {
                        TraceboxUiMessage.DiagnosticsEnabled
                    } else {
                        TraceboxUiMessage.DiagnosticsDisabled
                    }
                }

                TraceboxPolicyResult.RESTRICTED -> TraceboxUiMessage.PolicyRestricted
                TraceboxPolicyResult.PARTIAL -> TraceboxUiMessage.PolicyPartial
                TraceboxPolicyResult.FAILED -> TraceboxUiMessage.OperationFailed
            }
        }
    }

    fun deleteAllData() {
        viewModelScope.launch {
            mutableMessage.value = when (controller.deleteAllData()) {
                TraceboxDeleteResult.COMPLETE -> TraceboxUiMessage.DeleteComplete
                TraceboxDeleteResult.PENDING_FAILURE -> TraceboxUiMessage.DeletePending
                TraceboxDeleteResult.REJECTED -> TraceboxUiMessage.DeleteRejected
            }
        }
    }

    fun preparePackage() {
        viewModelScope.launch {
            when (val preparation = controller.preparePackage()) {
                is TraceboxPackagePreparation.Ready -> {
                    mutableMessage.value = TraceboxUiMessage.ReviewReady(
                        valueCount = preparation.includedValueCount,
                        bytes = preparation.includedBytes,
                    )
                    mutableEffects.send(TraceboxUiEffect.Review(preparation.approvalIntent))
                }

                TraceboxPackagePreparation.NotReady -> {
                    mutableMessage.value = TraceboxUiMessage.PackageNotReady
                }

                TraceboxPackagePreparation.Rejected -> {
                    mutableMessage.value = TraceboxUiMessage.PackageRejected
                }
            }
        }
    }

    fun onApprovalResult(resultCode: Int, resultData: Intent?) {
        viewModelScope.launch {
            val approvedData = resultData.takeIf { resultCode == Activity.RESULT_OK }
            mutableMessage.value = when (controller.createApprovedPackage(approvedData)) {
                TraceboxPackageCreationResult.CREATED -> TraceboxUiMessage.PackageCreated
                TraceboxPackageCreationResult.APPROVAL_CANCELLED ->
                    TraceboxUiMessage.ApprovalCancelled

                TraceboxPackageCreationResult.REJECTED -> TraceboxUiMessage.PackageRejected
                TraceboxPackageCreationResult.NOT_READY -> TraceboxUiMessage.PackageNotReady
            }
        }
    }

    fun requestSaveDestination() {
        val intent = controller.createSaveIntent()
        if (intent == null) {
            mutableMessage.value = TraceboxUiMessage.PackageNotReady
        } else {
            mutableEffects.trySend(TraceboxUiEffect.Save(intent))
        }
    }

    fun onSaveDestination(destination: Uri?) {
        if (destination == null) {
            mutableMessage.value = TraceboxUiMessage.SaveCancelled
            return
        }
        viewModelScope.launch {
            mutableMessage.value = when (val result = controller.save(destination)) {
                is TraceboxPackageSaveResult.Complete ->
                    TraceboxUiMessage.SaveComplete(result.bytesWritten)

                is TraceboxPackageSaveResult.Partial ->
                    TraceboxUiMessage.SavePartial(result.bytesWritten, result.cancelled)

                TraceboxPackageSaveResult.Failed -> TraceboxUiMessage.SaveFailed
                TraceboxPackageSaveResult.NotReady -> TraceboxUiMessage.PackageNotReady
            }
        }
    }

    fun sharePackage() {
        viewModelScope.launch {
            when (val result = controller.share()) {
                is TraceboxPackageShareResult.Ready -> {
                    mutableEffects.send(TraceboxUiEffect.Share(result.chooserIntent))
                }

                TraceboxPackageShareResult.NotReady -> {
                    mutableMessage.value = TraceboxUiMessage.PackageNotReady
                }
            }
        }
    }

    fun onShareChooserOpened() {
        mutableMessage.value = TraceboxUiMessage.ShareChooserOpened
    }

    fun onShareReturned() {
        mutableMessage.value = TraceboxUiMessage.ShareDeliveryUnknown
    }
}
