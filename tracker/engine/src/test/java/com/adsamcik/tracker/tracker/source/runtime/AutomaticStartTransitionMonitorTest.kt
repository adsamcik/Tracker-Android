package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationOwner
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationResult
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationSnapshot
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationStatus
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AutomaticStartTransitionMonitorTest {
	private val arbiter = mockk<ActivityRegistrationArbiter>()
	private val broker = mockk<SourceBroker>()
	private val subject = AutomaticStartTransitionMonitor(
		arbiter = arbiter,
		sourceBroker = broker,
		clockDomainProvider = BootClockDomainProvider { "boot:test" },
	)

	@Test
	fun `disabled transition API cannot fall back to continuous recognition control`() = runTest {
		coEvery { broker.replaceAutomaticControlDemand(any(), any(), false, any(), any(), any(), any(), any()) } returns null
		coEvery { arbiter.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR) } returns cleared()

		subject.reconcile(
			enabled = true,
			useTransitionApi = false,
			continuousIntervalSeconds = 5,
			transitions = setOf(walkingEnter()),
		)

		coVerify(exactly = 1) {
			broker.replaceAutomaticControlDemand(
				consumerId = "app:automatic-start:activity",
				source = SourceKind.ACTIVITY,
				enabled = false,
				bootId = "boot:test",
				elapsedRealtimeNanos = any(),
				wallTimeMs = any(),
				maximumAgeMs = any(),
				desiredLatencyMs = 5_000L,
			)
		}
		coVerify(exactly = 1) {
			arbiter.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)
		}
		coVerify(exactly = 0) { arbiter.setDemand(any(), any()) }
	}

	@Test
	fun `empty transition set clears automatic control instead of registering sampling`() = runTest {
		coEvery { broker.replaceAutomaticControlDemand(any(), any(), false, any(), any(), any(), any(), any()) } returns null
		coEvery { arbiter.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR) } returns cleared()

		subject.reconcile(
			enabled = true,
			useTransitionApi = true,
			continuousIntervalSeconds = 30,
			transitions = emptySet(),
		)

		coVerify(exactly = 1) {
			broker.replaceAutomaticControlDemand(any(), SourceKind.ACTIVITY, false, any(), any(), any(), any(), any())
		}
		coVerify(exactly = 0) { arbiter.setDemand(any(), any()) }
	}

	private fun walkingEnter() = ActivityTransitionData(
		DetectedActivityType.WALKING,
		ActivityTransitionType.ENTER,
	)

	private fun cleared() = ActivityRegistrationResult(
		status = ActivityRegistrationStatus.APPLIED,
		snapshot = ActivityRegistrationSnapshot(
			active = false,
			identity = null,
			owners = emptySet(),
			continuousRecognitionIntervalSeconds = null,
			transitions = emptySet(),
		),
	)
}
