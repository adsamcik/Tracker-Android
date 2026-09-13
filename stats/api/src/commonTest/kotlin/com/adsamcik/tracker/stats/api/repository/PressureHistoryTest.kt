package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PressureHistoryTest {
	@Test
	fun `retained window exposes direct trend range coverage gap and zone evidence`() {
		val window = pressureWindow()
		val history = PressureHistory(
			availability = HistoryAvailability.AVAILABLE,
			evidence = HistoryEvidence.RECORDED,
			productState = HistoryProductState.READY,
			coverage = PressureHistoryCoverage.COMPLETE,
			windows = listOf(window),
		)

		assertEquals(PressureHistoryPresentationState.READY, history.presentationState)
		assertEquals(setOf("Europe/Prague"), history.zoneAuthorities)
		assertEquals(PressureHistorySummary(1001f, 1000f, 999f, 1002f, 1), history.summary)
		assertEquals(-0.1, history.windows.single().slopeHectopascalsPerSecond)
		assertEquals(1.0, history.windows.single().actualToExpectedSampleRatio)
		assertEquals(40_000_000L, history.windows.single().maximumInterSampleGapNanos)
	}

	@Test
	fun `missing Pressure remains nonnumeric and unavailable`() {
		val history = PressureHistory(
			availability = HistoryAvailability.UNAVAILABLE,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.DEGRADED,
			coverage = PressureHistoryCoverage.NONE,
			windows = emptyList(),
			causes = setOf(PressureHistoryCause.PROVIDER_UNAVAILABLE),
		)

		assertEquals(PressureHistoryPresentationState.UNAVAILABLE, history.presentationState)
		assertNull(history.summary)
		assertEquals(emptySet(), history.zoneAuthorities)
	}

	@Test
	fun `failed and materializing Pressure remain distinct`() {
		val failed = PressureHistory(
			availability = HistoryAvailability.AVAILABLE,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.FAILED,
			coverage = PressureHistoryCoverage.UNKNOWN,
			windows = emptyList(),
			causes = setOf(PressureHistoryCause.PRESSURE_FACT_INTEGRITY_FAILED),
		)
		val materializing = PressureHistory(
			availability = HistoryAvailability.AVAILABLE,
			evidence = HistoryEvidence.STARTING,
			productState = HistoryProductState.MATERIALIZING,
			coverage = PressureHistoryCoverage.NONE,
			windows = emptyList(),
			causes = setOf(PressureHistoryCause.PRODUCT_LANE_BEHIND),
		)
		val partial = PressureHistory(
			availability = HistoryAvailability.AVAILABLE,
			evidence = HistoryEvidence.RECORDED,
			productState = HistoryProductState.PARTIAL,
			coverage = PressureHistoryCoverage.PARTIAL,
			windows = listOf(pressureWindow().copy(
				sampleCount = 3,
				qualification = PressureWindowQualification.PARTIAL,
			)),
			causes = setOf(PressureHistoryCause.PARTIAL_FACT),
		)

		assertEquals(PressureHistoryPresentationState.FAILED, failed.presentationState)
		assertEquals(PressureHistoryPresentationState.MATERIALIZING, materializing.presentationState)
		assertEquals(PressureHistoryPresentationState.PARTIAL, partial.presentationState)
	}

	@Test
	fun `ready Pressure cannot be fabricated without a retained observation`() {
		assertFailsWith<IllegalArgumentException> {
			PressureHistory(
				availability = HistoryAvailability.AVAILABLE,
				evidence = HistoryEvidence.NONE,
				productState = HistoryProductState.READY,
				coverage = PressureHistoryCoverage.COMPLETE,
				windows = emptyList(),
			)
		}
	}

	@Test
	fun `Pressure qualification must match exact capture and retained evidence`() {
		val pressure = PressureHistory(
			availability = HistoryAvailability.AVAILABLE,
			evidence = HistoryEvidence.RECORDED,
			productState = HistoryProductState.READY,
			coverage = PressureHistoryCoverage.COMPLETE,
			windows = listOf(pressureWindow()),
		)
		assertFailsWith<IllegalArgumentException> {
			PressureSessionHistory(
				segmentId = 7L,
				capture = HistoryCapture.Unverifiable,
				qualifiedSources = setOf(HistorySource.PRESSURE),
				pressure = pressure,
			)
		}
	}

	@Suppress("LongMethod")
	private fun pressureWindow() = PressureHistoryWindow(
		intervalStartTime = EpochMs(100L),
		intervalEndTime = EpochMs(200L),
		sampleCount = 4,
		expectedSampleCount = 4,
		meanHectopascals = 1000.5,
		sumSquaredDeviations = 2.0,
		minimumHectopascals = 999f,
		maximumHectopascals = 1002f,
		firstHectopascals = 1001f,
		latestHectopascals = 1000f,
		slopeHectopascalsPerSecond = -0.1,
		rSquared = 0.8,
		sensorAccuracy = PressureSensorAccuracy.HIGH,
		effectiveSamplePeriodMicros = 50_000,
		effectiveMaximumReportLatencyMicros = 0,
		targetWindowDurationNanos = 200_000_000L,
		maximumInterSampleGapNanos = 40_000_000L,
		closure = PressureWindowClosure.TARGET_ELAPSED,
		qualification = PressureWindowQualification.COMPLETE,
		sourceQualityFlags = 0L,
		sourceQualityConfidence = 1f,
		zoneId = "Europe/Prague",
	)
}
