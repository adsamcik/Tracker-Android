package com.adsamcik.tracker.tracker.pipeline

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

class SignalAdapterTest {

	@Test
	fun `full signal with all parameters populated`() {
		val signal = SignalAdapter.buildSignal(
			timestampMs = 1000L,
			latitude = 49.2,
			longitude = 16.6,
			accuracy = 5.0f,
			speed = 1.4f,
			altitude = 200f,
			distanceDelta = 10.0f,
			activityTypeCode = 7, // WALKING
			activityConfidence = 85,
			stepDelta = 5,
			totalStepsSinceBoot = 100L,
		)

		signal.timestampMs.raw shouldBe 1000L
		signal.location.shouldNotBeNull()
		signal.activity.shouldNotBeNull()
		signal.steps.shouldNotBeNull()
		signal.steps!!.stepDelta.raw shouldBe 5
		signal.steps!!.totalStepsSinceBoot shouldBe 100L
	}

	@Test
	fun `location only - no activity, no steps`() {
		val signal = SignalAdapter.buildSignal(
			timestampMs = 2000L,
			latitude = 50.0,
			longitude = 14.0,
			accuracy = 10.0f,
		)

		signal.location.shouldNotBeNull()
		signal.activity.shouldBeNull()
		signal.steps.shouldBeNull()
	}

	@Test
	fun `activity only - no location, no steps`() {
		val signal = SignalAdapter.buildSignal(
			timestampMs = 3000L,
			activityTypeCode = 7,
			activityConfidence = 90,
		)

		signal.location.shouldBeNull()
		signal.activity.shouldNotBeNull()
		signal.steps.shouldBeNull()
	}

	@Test
	fun `steps only - no location, no activity`() {
		val signal = SignalAdapter.buildSignal(
			timestampMs = 4000L,
			stepDelta = 10,
			totalStepsSinceBoot = 500L,
		)

		signal.location.shouldBeNull()
		signal.activity.shouldBeNull()
		signal.steps.shouldNotBeNull()
		signal.steps!!.stepDelta.raw shouldBe 10
	}

	@Test
	fun `completely empty signal - all nullable fields null`() {
		val signal = SignalAdapter.buildSignal(timestampMs = 5000L)

		signal.timestampMs.raw shouldBe 5000L
		signal.location.shouldBeNull()
		signal.activity.shouldBeNull()
		signal.steps.shouldBeNull()
	}

	@Test
	fun `zero stepDelta produces null steps`() {
		val signal = SignalAdapter.buildSignal(
			timestampMs = 6000L,
			stepDelta = 0,
			totalStepsSinceBoot = 200L,
		)

		signal.steps.shouldBeNull()
	}

	@Test
	fun `accuracy null with lat lon present produces null location`() {
		val signal = SignalAdapter.buildSignal(
			timestampMs = 7000L,
			latitude = 49.2,
			longitude = 16.6,
			accuracy = null,
		)

		signal.location.shouldBeNull()
	}

	@Test
	fun `producer persistence identity is carried into the tracking signal`() {
		val signal = SignalAdapter.buildSignal(
			timestampMs = 8_000L,
			persistenceSignalId = "producer-cycle-identity",
		)

		signal.persistenceSignalId shouldBe "producer-cycle-identity"
	}
}
