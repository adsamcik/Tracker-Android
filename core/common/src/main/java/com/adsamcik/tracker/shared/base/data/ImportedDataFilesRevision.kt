package com.adsamcik.tracker.shared.base.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Existing file readers reload when a local import or confirmed data restore changes their files. */
object ImportedDataFilesRevision {
    private val mutableRevision = MutableStateFlow(0L)
    val revisions: StateFlow<Long> = mutableRevision.asStateFlow()
    /** Signals a retained file change so readers refresh without starting acquisition. */
    fun changed() { mutableRevision.update { Math.addExact(it, 1L) } }
}
