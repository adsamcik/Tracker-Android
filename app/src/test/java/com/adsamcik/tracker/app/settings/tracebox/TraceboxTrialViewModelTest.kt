package com.adsamcik.tracker.app.settings.tracebox

import com.adsamcik.tracker.app.tracebox.TraceboxTrialController
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test

class TraceboxTrialViewModelTest {
    private val controller = FakeTraceboxTrialController()
    private val viewModel = TraceboxTrialViewModel(controller)

    @Test
    fun `trial starts disabled`() {
        viewModel.isEnabled.value shouldBe false
    }

    @Test
    fun `enabling delegates to current-run action`() {
        viewModel.setEnabled(true)

        controller.enableCalls shouldBe 1
        controller.isEnabled.value shouldBe true
    }

    @Test
    fun `disabling explicitly stops and clears`() {
        viewModel.setEnabled(true)
        viewModel.setEnabled(false)

        controller.disableAndClearCalls shouldBe 1
        controller.isEnabled.value shouldBe false
    }

    private class FakeTraceboxTrialController : TraceboxTrialController {
        private val mutableEnabled = MutableStateFlow(false)
        override val isEnabled: StateFlow<Boolean> = mutableEnabled
        var enableCalls = 0
        var disableAndClearCalls = 0

        override fun enableForCurrentAppRun() {
            enableCalls += 1
            mutableEnabled.value = true
        }

        override fun disableAndClear() {
            disableAndClearCalls += 1
            mutableEnabled.value = false
        }
    }
}
