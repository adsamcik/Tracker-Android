package com.adsamcik.tracker.app.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class DebugSettingsViewModel @Inject constructor() : ViewModel() {
    
    // Dialog states
    private val _showDeleteDataDialog = MutableStateFlow(false)
    val showDeleteDataDialog: StateFlow<Boolean> = _showDeleteDataDialog.asStateFlow()
    
    private val _showDummyDataDialog = MutableStateFlow(false)
    val showDummyDataDialog: StateFlow<Boolean> = _showDummyDataDialog.asStateFlow()
    
    fun showDeleteDataDialog() {
        _showDeleteDataDialog.value = true
    }
    
    fun hideDeleteDataDialog() {
        _showDeleteDataDialog.value = false
    }
    
    fun showDummyDataDialog() {
        _showDummyDataDialog.value = true
    }
    
    fun hideDummyDataDialog() {
        _showDummyDataDialog.value = false
    }
}
