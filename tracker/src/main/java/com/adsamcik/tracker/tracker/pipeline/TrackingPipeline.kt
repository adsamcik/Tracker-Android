package com.adsamcik.tracker.tracker.pipeline

import android.content.Context
import kotlin.time.measureTimedValue

/**
 * Executes a list of [PipelineStage]s sequentially, with per-stage
 * error isolation, timing metrics, and skip semantics.
 */
internal class TrackingPipeline(
	private val stages: List<PipelineStage>,
) {
	data class StageMetrics(
		val stageName: String,
		val durationMs: Long,
		val result: StageResult,
	)

	data class PipelineResult(
		val metrics: List<StageMetrics>,
		val cycleContext: CycleContext,
		val completedSuccessfully: Boolean,
	)

	@Suppress("TooGenericExceptionCaught")
	suspend fun execute(context: Context, cycleContext: CycleContext): PipelineResult {
		val metrics = mutableListOf<StageMetrics>()
		var completed = true

		for (stage in stages) {
			val (result, duration) = measureTimedValue {
				try {
					stage.process(context, cycleContext)
				} catch (e: Exception) {
					StageResult.Error(e, stage.name)
				}
			}

			metrics.add(StageMetrics(stage.name, duration.inWholeMilliseconds, result))

			when (result) {
				is StageResult.Continue -> { /* proceed to next stage */ }
				is StageResult.Skip -> {
					completed = false
					break
				}
				is StageResult.Error -> {
					// Error is captured in metrics; continue with remaining stages (fail-open)
				}
			}
		}

		return PipelineResult(metrics, cycleContext, completed)
	}
}
