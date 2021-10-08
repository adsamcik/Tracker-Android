package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.shared.base.misc.LocalizedString

/**
 * Export result data
 */
data class ExportResult(val isSuccess: Boolean, val message: LocalizedString? = null)
