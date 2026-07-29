package com.adsamcik.tracker.tracker.pipeline.stages

import android.content.Context
import com.adsamcik.tracker.shared.base.result.runWithResultAndReport
import com.adsamcik.tracker.tracker.component.PreTrackerComponent
import com.adsamcik.tracker.tracker.pipeline.CycleContext
import com.adsamcik.tracker.tracker.pipeline.PipelineStage
import com.adsamcik.tracker.tracker.pipeline.StageResult

/**
 * Runs all [PreTrackerComponent]s. If any component whose requirements are met
 * returns `false`, the cycle is skipped.
 */
internal class PreValidationStage(
	private val preComponents: List<PreTrackerComponent>,
) : PipelineStage {
	override val name: String = "PreValidation"

	override suspend fun process(context: Context, cycleContext: CycleContext): StageResult {
		for (component in preComponents) {
			if (!component.requirementsMet(cycleContext.cycle)) continue
			val pass = runWithResultAndReport {
				component.onNewData(cycleContext.cycle)
			}.getOrElse { true }
			if (!pass) {
				return StageResult.Skip(
					"Pre-component ${component::class.simpleName} rejected cycle",
				)
			}
		}
		return StageResult.Continue
	}
}
