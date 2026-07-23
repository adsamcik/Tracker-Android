package com.adsamcik.tracker.app.settings.tracebox

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.adsamcik.tracker.app.tracebox.TraceboxController
import com.adsamcik.tracker.app.tracebox.TraceboxUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class TraceboxSettingsViewModel @Inject constructor(
    private val controller: TraceboxController,
) : ViewModel() {
    val state: StateFlow<TraceboxUiState> = controller.state

    fun setEnabled(enabled: Boolean) = controller.setEnabled(enabled)
    fun prepareStandardPackage() = controller.prepareStandardPackage()
    fun approvalIntent(): Intent? = controller.approvalIntent()
    fun acceptApprovalResult(result: Intent?) = controller.acceptApprovalResult(result)
    fun shareIntent(): Intent? = controller.shareIntent()
    fun createSaveIntent(): Intent? = controller.createSaveIntent()
    fun save(destination: Uri) = controller.save(destination)
    fun cancelPackage() = controller.cancelPackage()
    fun deleteAllTraceboxData() = controller.deleteAllTraceboxData()
}
