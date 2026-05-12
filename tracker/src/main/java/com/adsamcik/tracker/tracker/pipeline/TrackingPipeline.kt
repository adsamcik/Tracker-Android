package com.adsamcik.tracker.tracker.pipeline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlin.time.measureTimedValue

/**
 * Executes a list of [PipelineStage]s sequentially, with per-stage
 * error isolation, timing metrics, and skip semantics.
 */
internal class TrackingPipeline(
	private val stages: List<PipelineStage>,
	private val collectMetrics: Boolean = true,
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

	suspend fun execute(context: Context, cycleContext: CycleContext): PipelineResult {
		val metrics = if (collectMetrics) mutableListOf<StageMetrics>() else null
		var completed = true

		for (stage in stages) {
			val result = if (collectMetrics) {
				val timedResult = measureTimedValue { runStage(context, cycleContext, stage) }
				metrics?.add(
					StageMetrics(stage.name, timedResult.duration.inWholeMilliseconds, timedResult.value)
				)
				timedResult.value
			} else {
				runStage(context, cycleContext, stage)
			}

			when (result) {
				is StageResult.Continue -> { /* proceed to next stage */ }
				is StageResult.Skip -> {
					completed = false
					break
				}
				is StageResult.Error -> {
					Log.w(TAG, "Stage ${result.stage} failed; continuing pipeline", result.exception)
				}
			}
		}

		return PipelineResult(metrics.orEmpty(), cycleContext, completed)
	}

	@Suppress("TooGenericExceptionCaught")
	private suspend fun runStage(
		context: Context,
		cycleContext: CycleContext,
		stage: PipelineStage,
	): StageResult {
		return try {
			stage.process(context, cycleContext)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			StageResult.Error(e, stage.name)
		}
	}

	private companion object {
		const val TAG = "TrackingPipeline"
	}
}
