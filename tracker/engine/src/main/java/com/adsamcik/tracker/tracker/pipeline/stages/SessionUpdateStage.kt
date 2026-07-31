package com.adsamcik.tracker.tracker.pipeline.stages

import android.content.Context
import com.adsamcik.tracker.tracker.component.consumer.SessionTrackerComponent
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.data.collection.toSnapshot as toCollectionSnapshot
import com.adsamcik.tracker.tracker.data.session.toSnapshot
import com.adsamcik.tracker.tracker.pipeline.CycleContext
import com.adsamcik.tracker.tracker.pipeline.PipelineStage
import com.adsamcik.tracker.tracker.pipeline.StageResult

/**
 * Updates the [SessionTrackerComponent] with the current cycle data,
 * then publishes the updated session and collection data via [TrackerServiceController].
 *
 * Sets [CycleContext.session] so downstream stages can access the session.
 */
internal class SessionUpdateStage(
	private val sessionComponent: SessionTrackerComponent,
	private val controller: TrackerServiceController,
) : PipelineStage {
	override suspend fun process(context: Context, cycleContext: CycleContext): StageResult {
		sessionComponent.onDataUpdated(cycleContext.cycle, cycleContext.collectionData)
		val session = sessionComponent.session
		cycleContext.session = session

		controller.updateSession(session.toSnapshot())
		controller.updateCollectionData(cycleContext.collectionData.toCollectionSnapshot())

		return StageResult.Continue
	}
}
