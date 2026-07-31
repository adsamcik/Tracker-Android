package com.adsamcik.tracker.tracker.pipeline

/**
 * Outcome of a single [PipelineStage] execution.
 */
internal sealed interface StageResult {
	/** Stage completed successfully, continue pipeline. */
	data object Continue : StageResult

	/** Stage determined this cycle should not advance to later stages. */
	data object Skip : StageResult
}
