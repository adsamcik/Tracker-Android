package com.adsamcik.tracker.tracker.pipeline

import android.content.Context
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticFailureCode
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticLog
import com.adsamcik.tracker.diagnostics.TrackingDiagnosticFailureReason
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
				TrackerDiagnosticLog.failure(
					TrackerDiagnosticFailureCode.TRACKING_PIPELINE_STAGE_FAILED,
					TrackingDiagnosticFailureReason.PROCESSING_FAILURE,
				)
				StageResult.Continue
			}
			if (result == StageResult.Skip) break
		}
	}
}
