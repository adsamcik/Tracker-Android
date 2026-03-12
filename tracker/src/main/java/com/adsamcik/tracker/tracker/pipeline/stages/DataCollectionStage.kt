package com.adsamcik.tracker.tracker.pipeline.stages

import android.content.Context
import com.adsamcik.tracker.shared.utils.extension.tryWithReport
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.pipeline.CycleContext
import com.adsamcik.tracker.tracker.pipeline.PipelineStage
import com.adsamcik.tracker.tracker.pipeline.StageResult

/**
 * Runs all [DataTrackerComponent]s whose requirements are met,
 * enriching [CycleContext.collectionData] with aggregated sensor data.
 */
internal class DataCollectionStage(
	private val dataComponents: List<DataTrackerComponent>,
) : PipelineStage {
	override val name: String = "DataCollection"

	override suspend fun process(context: Context, cycleContext: CycleContext): StageResult {
		dataComponents
			.asSequence()
			.filter { it.requirementsMet(cycleContext.cycle) }
			.forEach {
				tryWithReport {
					it.onDataUpdated(cycleContext.cycle, cycleContext.collectionData)
				}
			}
		return StageResult.Continue
	}
}
