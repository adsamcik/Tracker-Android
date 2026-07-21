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
	 *
	 * Exporters that perform their own database reads may report location progress so
	 * scheduled exports can advance their incremental cursor accurately.
	 */
	open class Success(
		val recordCount: Int? = null,
		val maxTimeMs: Long? = null,
		val maxId: Long? = null,
	) : ExportResult() {
		// Preserve existing `ExportResult.Success` expression call sites while allowing
		// exporters that own their reads to return per-operation progress instances.
		companion object : Success()

		override fun equals(other: Any?): Boolean =
			other is Success &&
				recordCount == other.recordCount &&
				maxTimeMs == other.maxTimeMs &&
				maxId == other.maxId

		override fun hashCode(): Int {
			var result = recordCount ?: 0
			result = 31 * result + (maxTimeMs?.hashCode() ?: 0)
			result = 31 * result + (maxId?.hashCode() ?: 0)
			return result
		}
	}

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
