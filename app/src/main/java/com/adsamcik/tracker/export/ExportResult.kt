package com.adsamcik.tracker.export

import androidx.documentfile.provider.DocumentFile

/**
 * Export result data
 */
data class ExportResult(val file: DocumentFile, val mime: String)
