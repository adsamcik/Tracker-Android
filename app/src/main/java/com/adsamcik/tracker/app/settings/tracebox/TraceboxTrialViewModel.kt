package com.adsamcik.tracker.app.settings.tracebox

import androidx.lifecycle.ViewModel
import com.adsamcik.tracker.app.tracebox.TraceboxTrialController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class TraceboxTrialViewModel @Inject constructor(
    private val controller: TraceboxTrialController,
) : ViewModel() {
    val isEnabled: StateFlow<Boolean> = controller.isEnabled

    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            controller.enableForCurrentAppRun()
        } else {
            controller.disableAndClear()
        }
    }
}
