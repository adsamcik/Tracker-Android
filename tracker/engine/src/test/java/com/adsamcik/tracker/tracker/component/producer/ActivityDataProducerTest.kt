package com.adsamcik.tracker.tracker.component.producer

import com.adsamcik.tracker.activity.api.backend.ActivityUpdate
import com.adsamcik.tracker.activity.api.backend.ActivityUpdateSource
import com.adsamcik.tracker.activity.api.backend.RecognizedActivity
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder
import com.adsamcik.tracker.stats.api.DetectedActivityType
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityDataProducerTest {
	private lateinit var producer: ActivityDataProducer

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(UnconfinedTestDispatcher())
		producer = ActivityDataProducer(
			changeReceiver = mockk<TrackerDataProducerObserver>(relaxed = true),
		)
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun `marks only the first cycle after an activity callback as fresh`() {
		val activity = ActivityInfo(DetectedActivity.WALKING, confidence = 80)
		producer.recordActivity(activity, Time.elapsedRealtimeMillis)

		val firstBuilder = builder()
		producer.onDataRequest(firstBuilder)

		firstBuilder.activity shouldBe activity
		firstBuilder.activityFresh shouldBe true

		val secondBuilder = builder()
		producer.onDataRequest(secondBuilder)

		secondBuilder.activity shouldBe activity
		secondBuilder.activityFresh shouldBe false
	}

	@Test
	fun `ignores transition-derived updates in confidence-based session collection`() {
		producer.recordActivity(
			ActivityUpdate(
				activity = RecognizedActivity(DetectedActivityType.WALKING, confidence = 100),
				elapsedTimeMillis = Time.elapsedRealtimeMillis,
				source = ActivityUpdateSource.TRANSITION,
			),
		)

		val builder = builder()
		producer.onDataRequest(builder)

		builder.activity shouldBe ActivityInfo.UNKNOWN
		builder.activityFresh shouldBe false
	}

	private fun builder() = TrackingCycleBuilder(
		timestampMs = Time.nowMillis,
		elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
	)
}
