package com.adsamcik.tracker.impexp.importer

/**
 * Structured result of an import operation, tracking per-record outcomes.
 */
data class ImportResult(
	val successCount: Int = 0,
	val skippedCount: Int = 0,
	val failedCount: Int = 0,
	val errors: List<String> = emptyList()
) {
	val totalProcessed: Int get() = successCount + skippedCount + failedCount

	operator fun plus(other: ImportResult): ImportResult = ImportResult(
		successCount = successCount + other.successCount,
		skippedCount = skippedCount + other.skippedCount,
		failedCount = failedCount + other.failedCount,
		errors = errors + other.errors
	)

	companion object {
		val EMPTY = ImportResult()
	}
}
