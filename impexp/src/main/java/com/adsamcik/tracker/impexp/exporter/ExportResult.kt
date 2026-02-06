package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.shared.base.misc.LocalizedString

/**
 * Sealed result type for export operations.
 *
 * Per coding standards Section 9: cross-module operations return sealed Result types.
 * Callers should handle both success and error cases explicitly via when-expressions.
 */
sealed class ExportResult {
	/**
	 * Export completed successfully.
	 */
	data object Success : ExportResult()

	/**
	 * Export failed with an optional localized message for the user.
	 *
	 * @param message User-facing description of the failure. Null when the failure
	 *   reason cannot be meaningfully communicated (e.g. internal I/O error
	 *   already logged).
	 */
	data class Error(val message: LocalizedString? = null) : ExportResult()

	/**
	 * Convenience property for migration: returns true when the result is [Success].
	 */
	val isSuccess: Boolean get() = this is Success
}
