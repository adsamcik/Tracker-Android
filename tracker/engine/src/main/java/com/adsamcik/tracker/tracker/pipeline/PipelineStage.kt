package com.adsamcik.tracker.tracker.pipeline

import android.content.Context

/**
 * A single stage in the tracking pipeline.
 * Stages are composed into a pipeline and executed sequentially per tracking cycle.
 */
internal interface PipelineStage {
	/**
	 * Process a tracking cycle. Returns [StageResult] indicating whether the pipeline should
	 * continue or skip the remaining stages.
	 */
	suspend fun process(context: Context, cycleContext: CycleContext): StageResult
}
