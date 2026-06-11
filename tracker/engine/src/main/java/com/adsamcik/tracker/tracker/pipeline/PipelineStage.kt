package com.adsamcik.tracker.tracker.pipeline

import android.content.Context

/**
 * A single stage in the tracking pipeline.
 * Stages are composed into a pipeline and executed sequentially per tracking cycle.
 */
internal interface PipelineStage {
	/** Human-readable name for logging and metrics. */
	val name: String

	/**
	 * Process a tracking cycle. Returns [StageResult] indicating whether
	 * the pipeline should continue, skip remaining stages, or record an error.
	 */
	suspend fun process(context: Context, cycleContext: CycleContext): StageResult
}
