package com.adsamcik.tracker.app.settings.tracebox

import android.content.Intent
import android.net.Uri
import com.adsamcik.tracker.app.tracebox.TraceboxAvailability
import com.adsamcik.tracker.app.tracebox.TraceboxController
import com.adsamcik.tracker.app.tracebox.TraceboxUiState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.Test

class TraceboxSettingsViewModelTest {
    @Test
    fun `delegates explicit enablement to the bounded controller`() {
        val controller = FakeTraceboxController()
        val viewModel = TraceboxSettingsViewModel(controller)

        viewModel.setEnabled(true)

        controller.enabled shouldBe true
        viewModel.state.value.availability shouldBe TraceboxAvailability.DISABLED
    }

    private class FakeTraceboxController : TraceboxController {
        private val mutableState = MutableStateFlow(
            TraceboxUiState(TraceboxAvailability.DISABLED, enabled = false),
        )
        var enabled = false
        override val state: StateFlow<TraceboxUiState> = mutableState

        override fun setEnabled(enabled: Boolean) {
            this.enabled = enabled
        }

        override fun prepareStandardPackage() = Unit
        override fun approvalIntent(): Intent? = null
        override fun acceptApprovalResult(result: Intent?) = Unit
        override fun shareIntent(): Intent? = null
        override fun createSaveIntent(): Intent? = null
        override fun save(destination: Uri) = Unit
        override fun cancelPackage() = Unit
        override fun deleteAllTraceboxData() = Unit
    }
}
