package com.adsamcik.tracker.impexp.format

import androidx.annotation.StringRes

/**
 * Metadata describing a supported import/export file format.
 *
 * Instances are registered once in [FormatRegistry] and queried
 * at runtime to resolve exporters and importers without hard-coded
 * `when` blocks scattered across workers.
 */
data class FormatDescriptor(
	val id: String,
	@StringRes val displayNameRes: Int,
	val mimeType: String,
	val extensions: Set<String>,
	val supportsExport: Boolean,
	val supportsImport: Boolean,
	val supportsDateRange: Boolean,
)
