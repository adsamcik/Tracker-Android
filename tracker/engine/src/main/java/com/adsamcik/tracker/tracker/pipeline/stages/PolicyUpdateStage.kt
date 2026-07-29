package com.adsamcik.tracker.tracker.pipeline.stages

import android.content.Context
import com.adsamcik.tracker.shared.base.result.runWithReport
import com.adsamcik.tracker.tracker.pipeline.CycleContext
import com.adsamcik.tracker.tracker.pipeline.PipelineStage
import com.adsamcik.tracker.tracker.pipeline.StageResult
import com.adsamcik.tracker.tracker.policy.TrackingPolicyManager
import com.adsamcik.tracker.tracker.service.TrackerPolicyFeeder
import kotlinx.coroutines.CoroutineScope

/**
 * Feeds the current cycle's collection data into the adaptive tracking policy
 * manager so it can adjust tracking parameters (interval, tier).
 */
internal class PolicyUpdateStage(
	private val policyFeeder: TrackerPolicyFeeder,
	private val trackingPolicyManager: TrackingPolicyManager?,
	private val scope: CoroutineScope,
) : PipelineStage {
	override val name: String = "PolicyUpdate"

	override suspend fun process(context: Context, cycleContext: CycleContext): StageResult {
		trackingPolicyManager?.let { policyMgr ->
			runWithReport {
				policyFeeder.feed(
					policyMgr,
					cycleContext.collectionData,
					cycleContext.cycle,
					scope,
				)
			}
		}
		return StageResult.Continue
	}
}
