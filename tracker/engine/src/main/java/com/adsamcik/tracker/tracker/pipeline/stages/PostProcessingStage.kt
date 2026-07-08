package com.adsamcik.tracker.tracker.pipeline.stages

import android.content.Context
import android.util.Log
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
	private companion object {
		const val TAG = "PostProcessingStage"
	}

	override val name: String = "PostProcessing"

	override suspend fun process(context: Context, cycleContext: CycleContext): StageResult {
		val session = requireNotNull(cycleContext.session) {
			"Session must be set by SessionUpdateStage before PostProcessingStage"
		}
		val cycle = cycleContext.cycle
		val collectionData = cycleContext.collectionData

		if (notificationComponent.requirementsMet(cycle)) {
			try {
				notificationComponent.onNewData(context, session, collectionData, cycle)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.w(TAG, "Notification post-processing failed", e)
			}
		}
		skiSegmentWriter?.let { writer ->
			if (writer.requirementsMet(cycle)) {
				try {
					writer.onNewData(context, session, collectionData, cycle)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Log.w(TAG, "Ski segment writer post-processing failed", e)
				}
			}
		}
		skiTrackingComponent?.let { ski ->
			if (ski.requirementsMet(cycle)) {
				try {
					ski.onNewData(context, session, collectionData, cycle)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Log.w(TAG, "Ski tracking post-processing failed", e)
				}
			}
		}
		sailingTrackingComponent?.let { sailing ->
			if (sailing.requirementsMet(cycle)) {
				try {
					sailing.onNewData(context, session, collectionData, cycle)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Log.w(TAG, "Sailing tracking post-processing failed", e)
				}
			}
		}
		planeTrackingComponent?.let { plane ->
			if (plane.requirementsMet(cycle)) {
				try {
					plane.onNewData(context, session, collectionData, cycle)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Log.w(TAG, "Plane tracking post-processing failed", e)
				}
			}
		}

		return StageResult.Continue
	}
}

