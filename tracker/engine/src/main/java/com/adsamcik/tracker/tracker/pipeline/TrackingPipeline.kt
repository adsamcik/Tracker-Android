package com.adsamcik.tracker.tracker.pipeline

import dev.tracebox.Tracebox
import android.content.Context
import kotlinx.coroutines.CancellationException

/**
 * Executes [PipelineStage]s sequentially with fail-open stage isolation and skip semantics.
 */
internal class TrackingPipeline(
	private val stages: List<PipelineStage>,
) {
	suspend fun execute(context: Context, cycleContext: CycleContext) {
		for (stage in stages) {
			val result = try {
				stage.process(context, cycleContext)
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				Tracebox.log.error(error, "Tracking pipeline stage failed")
				StageResult.Continue
			}
			if (result == StageResult.Skip) break
		}
	}
}
