package com.adsamcik.tracker.tracker.pipeline.stages

import android.content.Context
import com.adsamcik.tracker.shared.utils.extension.tryWithReport
import com.adsamcik.tracker.tracker.component.consumer.post.NotificationComponent
import com.adsamcik.tracker.tracker.component.consumer.post.SkiSegmentWriter
import com.adsamcik.tracker.tracker.component.consumer.post.SkiTrackingComponent
import com.adsamcik.tracker.tracker.pipeline.CycleContext
import com.adsamcik.tracker.tracker.pipeline.PipelineStage
import com.adsamcik.tracker.tracker.pipeline.StageResult

/**
 * Runs post-processing components (notification, ski tracking) that
 * operate on the finalized [CycleContext.collectionData] and [CycleContext.session].
 */
internal class PostProcessingStage(
	private val notificationComponent: NotificationComponent,
	private val skiSegmentWriter: SkiSegmentWriter?,
	private val skiTrackingComponent: SkiTrackingComponent?,
) : PipelineStage {
	override val name: String = "PostProcessing"

	override suspend fun process(context: Context, cycleContext: CycleContext): StageResult {
		val session = requireNotNull(cycleContext.session) {
			"Session must be set by SessionUpdateStage before PostProcessingStage"
		}
		val cycle = cycleContext.cycle
		val collectionData = cycleContext.collectionData

		if (notificationComponent.requirementsMet(cycle)) {
			tryWithReport {
				notificationComponent.onNewData(context, session, collectionData, cycle)
			}
		}
		skiSegmentWriter?.let { writer ->
			if (writer.requirementsMet(cycle)) {
				tryWithReport {
					writer.onNewData(context, session, collectionData, cycle)
				}
			}
		}
		skiTrackingComponent?.let { ski ->
			if (ski.requirementsMet(cycle)) {
				tryWithReport {
					ski.onNewData(context, session, collectionData, cycle)
				}
			}
		}

		return StageResult.Continue
	}
}
