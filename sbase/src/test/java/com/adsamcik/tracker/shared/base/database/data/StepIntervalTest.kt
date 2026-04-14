package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("StepInterval - step counter delta entity")
class StepIntervalTest {

	private fun interval(
		id: Long = 0,
		startTimeMs: Long = 1000L,
		endTimeMs: Long = 2000L,
		stepCount: Int = 150,
		sensorValueStart: Int = 1000,
		sensorValueEnd: Int = 1150,
		sensorReset: Boolean = false,
		createdAt: Long = 1000L
	) = StepInterval(id, startTimeMs, endTimeMs, stepCount, sensorValueStart, sensorValueEnd, sensorReset, createdAt)

	@Nested
	@DisplayName("Construction")
	inner class Construction {
		@Test
		fun `stores all fields`() {
			val i = interval()
			i.startTimeMs shouldBe 1000L
			i.endTimeMs shouldBe 2000L
			i.stepCount shouldBe 150
			i.sensorValueStart shouldBe 1000
			i.sensorValueEnd shouldBe 1150
			i.sensorReset shouldBe false
			i.createdAt shouldBe 1000L
		}

		@Test
		fun `sensorReset can be true`() {
			interval(sensorReset = true).sensorReset shouldBe true
		}
	}

	@Nested
	@DisplayName("Data class features")
	inner class DataClassFeatures {
		@Test
		fun `equality`() {
			interval() shouldBe interval()
		}

		@Test
		fun `inequality`() {
			interval(stepCount = 100) shouldNotBe interval(stepCount = 200)
		}

		@Test
		fun `copy`() {
			val i = interval().copy(sensorReset = true)
			i.sensorReset shouldBe true
			i.stepCount shouldBe 150
		}
	}
}
