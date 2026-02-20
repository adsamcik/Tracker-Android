package com.adsamcik.tracker.app.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Contract: ViewModel for debug settings screen
// Inputs: None (just UI state management)
// Outputs: StateFlows for dialog visibility
// Errors: None
class DebugSettingsViewModel : ViewModel() {
    
    // Dialog states
    private val _showDeleteDataDialog = MutableStateFlow(false)
    val showDeleteDataDialog: StateFlow<Boolean> = _showDeleteDataDialog.asStateFlow()
    
    fun showDeleteDataDialog() {
        _showDeleteDataDialog.value = true
    }
    
    fun hideDeleteDataDialog() {
        _showDeleteDataDialog.value = false
    }
}
