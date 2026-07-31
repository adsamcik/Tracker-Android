package com.adsamcik.tracker.tracker.pipeline

import android.content.Context
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticCode
import com.adsamcik.tracker.diagnostics.TrackerDiagnostics
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
			} catch (_: Exception) {
				TrackerDiagnostics.record(TrackerDiagnosticCode.TRACKING_PIPELINE_STAGE_FAILED)
				StageResult.Continue
			}
			if (result == StageResult.Skip) break
		}
	}
}
