package com.adsamcik.tracker.activity.api.registration

import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.stats.api.DetectedActivityType
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import javax.inject.Provider
import org.junit.Test

class ActivityMotionAssistanceQualificationTest {
	@Test
	fun `only observed fresh evidence inside its authority boundary qualifies`() {
		qualifiesMotionAssistanceTime(100L, 100L, 100L) shouldBe true
		qualifiesMotionAssistanceTime(99L, 100L, 100L) shouldBe false
		qualifiesMotionAssistanceTime(101L, 100L, 0L) shouldBe false
		qualifiesMotionAssistanceTime(1L, 30_000_000_001L, 0L) shouldBe true
		qualifiesMotionAssistanceTime(1L, 30_000_000_002L, 0L) shouldBe false
	}

	@Test
	fun `desired owner without acknowledged transition set never authorizes hints`() {
		val subject = DefaultActivityMotionAssistance(mockk(), Provider { mockk<AppDatabase>() }, mockk(), mockk(),
			Provider { mockk() })
		val authority = ActivityMotionAssistanceAuthority(1L, LocationMotionPurpose.CAPTURE, 1L, 0L,
			"boot", 10L, "host")
		val transition = ActivityTransitionData(DetectedActivityType.WALKING, ActivityTransitionType.ENTER)
		val snapshot = ActivityRegistrationSnapshot(true,
			ActivityRegistrationIdentity("instance", 1L, 0L, "boot", "physical"),
			setOf(ActivityRegistrationOwner.LOCATION_MOTION_ASSISTANCE), null, emptySet())
		subject.acknowledge(authority, snapshot, setOf(transition))
		subject.currentAuthority shouldBe null
		subject.acknowledge(authority, snapshot.copy(transitions = setOf(transition)), setOf(transition))
		subject.currentAuthority shouldBe authority
		subject.clear()
		subject.currentAuthority shouldBe null
	}
}
