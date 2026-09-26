package com.adsamcik.tracker.activity.receiver

import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidence
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.activity.api.ingress.ActivityTransitionEvidence
import com.adsamcik.tracker.stats.api.DetectedActivityType
import io.kotest.matchers.shouldBe
import org.junit.Test

class ActivityMotionAssistanceRoutingTest {
	private val walking = ActivityTransitionEvidence(DetectedActivityType.WALKING, ActivityTransitionType.ENTER, 10L)
	private val still = ActivityTransitionEvidence(DetectedActivityType.STILL, ActivityTransitionType.ENTER, 11L)
	private val batch = ActivityRecognitionEvidenceBatch(20L, 20L,
		recognitions = listOf(ActivityRecognitionEvidence(DetectedActivityType.WALKING, 90, 10L)),
		transitions = listOf(walking, still))

	@Test
	fun `helper only callback has no durable members`() {
		batch.forDurableActivityOwners(false, emptySet()).eventCount shouldBe 0
		batch.eventCount shouldBe 3
	}

	@Test
	fun `shared callback retains only independently requested durable transitions`() {
		val durable = batch.forDurableActivityOwners(true,
			setOf(ActivityTransitionData(DetectedActivityType.WALKING, ActivityTransitionType.ENTER)))
		durable.recognitions shouldBe batch.recognitions
		durable.transitions shouldBe listOf(walking)
		durable.automaticRecognitionEligible shouldBe false
		durable.automaticTransitions shouldBe emptySet()
	}

	@Test
	fun `legacy callback preserves its original durable selection`() {
		batch.forDurableActivityOwners(true, null) shouldBe batch
	}
}
