package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PressureProviderRequestTest {
	@Test
	fun `no FIFO forces immediate delivery and exposes both unrealized demands`() {
		val plan = plan(samplePeriodMicros = 50_000, reportLatencyMicros = 60_000_000)

		val request = plan.toPressureProviderRequest(
			sensorMinimumDelayMicros = 200_000,
			sensorMaximumDelayMicros = 10_000_000,
			fifoMaxEventCount = 0,
		)

		assertEquals(200_000, request.samplePeriodMicros)
		assertEquals(0, request.maximumReportLatencyMicros)
		assertFalse(request.batchingEnabled)
		assertEquals(
			setOf(
				SourceDegradedReason.DEMAND_FLOOR_UNSATISFIED,
				SourceDegradedReason.PROVIDER_UNAVAILABLE,
			),
			request.degradedReasons,
		)
		assertEquals(
			plan.copy(
				hardwareSamplePeriodMicros = 200_000,
				maximumReportLatencyMicros = 0,
			).physicalConfigurationFingerprint(),
			request.physicalConfigurationFingerprint,
		)
	}

	@Test
	fun `FIFO preserves requested latency because capacity is not an equivalence bound`() {
		val request = plan(
			samplePeriodMicros = 200_000,
			reportLatencyMicros = 60_000_000,
		).toPressureProviderRequest(
			sensorMinimumDelayMicros = 50_000,
			sensorMaximumDelayMicros = 10_000_000,
			fifoMaxEventCount = 10,
		)

		assertEquals(200_000, request.samplePeriodMicros)
		assertEquals(60_000_000, request.maximumReportLatencyMicros)
		assertTrue(request.batchingEnabled)
		assertEquals(emptySet(), request.degradedReasons)
	}

	@Test
	fun `maximum delay caps unrealized low power cadence and marks provider unavailable`() {
		val request = plan(
			samplePeriodMicros = 60_000_000,
			reportLatencyMicros = 0,
		).toPressureProviderRequest(
			sensorMinimumDelayMicros = 50_000,
			sensorMaximumDelayMicros = 1_000_000,
			fifoMaxEventCount = 0,
		)

		assertEquals(1_000_000, request.samplePeriodMicros)
		assertEquals(0, request.maximumReportLatencyMicros)
		assertEquals(setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE), request.degradedReasons)
	}

	@Test
	fun `maximum-delay equivalent modes share one physical fingerprint`() {
		val supported = plan(
			revision = 1L,
			samplePeriodMicros = 1_000_000,
			reportLatencyMicros = 0,
			aggregationWindowMs = 2_000L,
		).toPressureProviderRequest(
			sensorMinimumDelayMicros = 50_000,
			sensorMaximumDelayMicros = 1_000_000,
			fifoMaxEventCount = 0,
		)
		val unrealizedSaver = plan(
			revision = 2L,
			samplePeriodMicros = 60_000_000,
			reportLatencyMicros = 0,
			aggregationWindowMs = 60_000L,
		).toPressureProviderRequest(
			sensorMinimumDelayMicros = 50_000,
			sensorMaximumDelayMicros = 1_000_000,
			fifoMaxEventCount = 0,
		)

		assertEquals(supported.samplePeriodMicros, unrealizedSaver.samplePeriodMicros)
		assertEquals(
			supported.physicalConfigurationFingerprint,
			unrealizedSaver.physicalConfigurationFingerprint,
		)
		assertEquals(emptySet(), supported.degradedReasons)
		assertEquals(setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE), unrealizedSaver.degradedReasons)
	}

	@Test
	fun `minimum-delay equivalent modes share a fingerprint with an explicit floor reason`() {
		val unrealizedResponsive = plan(
			samplePeriodMicros = 50_000,
			reportLatencyMicros = 0,
		).toPressureProviderRequest(
			sensorMinimumDelayMicros = 1_000_000,
			sensorMaximumDelayMicros = 10_000_000,
			fifoMaxEventCount = 0,
		)
		val supported = plan(
			samplePeriodMicros = 1_000_000,
			reportLatencyMicros = 0,
		).toPressureProviderRequest(
			sensorMinimumDelayMicros = 1_000_000,
			sensorMaximumDelayMicros = 10_000_000,
			fifoMaxEventCount = 0,
		)

		assertEquals(
			unrealizedResponsive.physicalConfigurationFingerprint,
			supported.physicalConfigurationFingerprint,
		)
		assertEquals(
			setOf(SourceDegradedReason.DEMAND_FLOOR_UNSATISFIED),
			unrealizedResponsive.degradedReasons,
		)
		assertEquals(emptySet(), supported.degradedReasons)
	}

	@Test
	fun `a supported sampling difference remains a distinct physical request`() {
		val responsive = plan(
			samplePeriodMicros = 50_000,
			reportLatencyMicros = 1_000_000,
		).toPressureProviderRequest(
			sensorMinimumDelayMicros = 10_000,
			sensorMaximumDelayMicros = 10_000_000,
			fifoMaxEventCount = 100,
		)
		val balanced = plan(
			samplePeriodMicros = 200_000,
			reportLatencyMicros = 1_000_000,
		).toPressureProviderRequest(
			sensorMinimumDelayMicros = 10_000,
			sensorMaximumDelayMicros = 10_000_000,
			fifoMaxEventCount = 100,
		)

		assertNotEquals(responsive.physicalConfigurationFingerprint, balanced.physicalConfigurationFingerprint)
	}

	@Test
	fun `nonpositive and inconsistent delay bounds are handled safely`() {
		val unknownBounds = plan(
			samplePeriodMicros = 200_000,
			reportLatencyMicros = 0,
		).toPressureProviderRequest(
			sensorMinimumDelayMicros = 0,
			sensorMaximumDelayMicros = 0,
			fifoMaxEventCount = -1,
		)
		val inconsistentBounds = plan(
			samplePeriodMicros = 2_000_000,
			reportLatencyMicros = 0,
		).toPressureProviderRequest(
			sensorMinimumDelayMicros = 1_000_000,
			sensorMaximumDelayMicros = 500_000,
			fifoMaxEventCount = 0,
		)

		assertEquals(200_000, unknownBounds.samplePeriodMicros)
		assertEquals(emptySet(), unknownBounds.degradedReasons)
		assertEquals(2_000_000, inconsistentBounds.samplePeriodMicros)
		assertEquals(
			setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE),
			inconsistentBounds.degradedReasons,
		)
	}

	private fun plan(
		revision: Long = 1L,
		samplePeriodMicros: Int,
		reportLatencyMicros: Int,
		aggregationWindowMs: Long = 5_000L,
	) = PressurePlan(
		revision = revision,
		enabled = true,
		hardwareSamplePeriodMicros = samplePeriodMicros,
		maximumReportLatencyMicros = reportLatencyMicros,
		aggregationWindowMs = aggregationWindowMs,
		movementGatedBurst = false,
	)
}
