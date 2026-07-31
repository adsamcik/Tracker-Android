package com.adsamcik.tracker.tracker.pipeline.stages

import android.content.Context
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticCode
import com.adsamcik.tracker.diagnostics.TrackerDiagnostics
import com.adsamcik.tracker.tracker.component.consumer.post.NotificationComponent
import com.adsamcik.tracker.tracker.component.consumer.post.PlaneTrackingComponent
import com.adsamcik.tracker.tracker.component.consumer.post.SailingTrackingComponent
import com.adsamcik.tracker.tracker.component.consumer.post.SkiSegmentWriter
import com.adsamcik.tracker.tracker.component.consumer.post.SkiTrackingComponent
import com.adsamcik.tracker.tracker.pipeline.CycleContext
import com.adsamcik.tracker.tracker.pipeline.PipelineStage
import com.adsamcik.tracker.tracker.pipeline.StageResult
import kotlinx.coroutines.CancellationException

/**
 * Runs post-processing components (notification, ski tracking, sailing tracking, plane
 * tracking) that operate on the finalized [CycleContext.collectionData] and
 * [CycleContext.session].
 */
internal class PostProcessingStage(
	private val notificationComponent: NotificationComponent,
	private val skiSegmentWriter: SkiSegmentWriter?,
	private val skiTrackingComponent: SkiTrackingComponent?,
	private val sailingTrackingComponent: SailingTrackingComponent?,
	private val planeTrackingComponent: PlaneTrackingComponent?,
) : PipelineStage {
	override suspend fun process(context: Context, cycleContext: CycleContext): StageResult {
		suspend fun runIsolated(block: suspend () -> Unit) {
			try {
				block()
			} catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				TrackerDiagnostics.record(TrackerDiagnosticCode.TRACKING_PIPELINE_STAGE_FAILED)
			}
		}

		val session = requireNotNull(cycleContext.session) {
			"Session must be set by SessionUpdateStage before PostProcessingStage"
		}
		val cycle = cycleContext.cycle
		val collectionData = cycleContext.collectionData

		if (notificationComponent.requirementsMet(cycle)) {
			runIsolated {
				notificationComponent.onNewData(context, session, collectionData, cycle)
			}
		}
		skiSegmentWriter?.let { writer ->
			if (writer.requirementsMet(cycle)) {
				runIsolated {
					writer.onNewData(context, session, collectionData, cycle)
				}
			}
		}
		skiTrackingComponent?.let { ski ->
			if (ski.requirementsMet(cycle)) {
				runIsolated {
					ski.onNewData(context, session, collectionData, cycle)
				}
			}
		}
		sailingTrackingComponent?.let { sailing ->
			if (sailing.requirementsMet(cycle)) {
				runIsolated {
					sailing.onNewData(context, session, collectionData, cycle)
				}
			}
		}
		planeTrackingComponent?.let { plane ->
			if (plane.requirementsMet(cycle)) {
				runIsolated {
					plane.onNewData(context, session, collectionData, cycle)
				}
			}
		}

		return StageResult.Continue
	}
}
