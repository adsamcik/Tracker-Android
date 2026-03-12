package com.adsamcik.tracker.tracker.pipeline

/**
 * Outcome of a single [PipelineStage] execution.
 */
internal sealed interface StageResult {
	/** Stage completed successfully, continue pipeline. */
	data object Continue : StageResult

	/** Stage determined this cycle should be skipped (e.g., pre-validation failed). */
	data class Skip(val reason: String) : StageResult

	/** Stage encountered an error but pipeline should continue with remaining stages. */
	data class Error(val exception: Throwable, val stage: String) : StageResult
}
