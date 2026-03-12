package com.adsamcik.tracker.app.debug

import androidx.lifecycle.ViewModel
import com.adsamcik.tracker.tracker.controller.LockManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class DebugViewModel @Inject constructor(
    val lockManager: LockManager,
) : ViewModel()
