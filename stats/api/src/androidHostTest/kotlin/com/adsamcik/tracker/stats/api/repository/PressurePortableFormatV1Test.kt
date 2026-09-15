package com.adsamcik.tracker.stats.api.repository

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class PressurePortableFormatV1Test {
	@Test
	fun `opaque identities are kind scoped and hide local provenance`() {
		val local = "logical-device-visible-id"
		val logical = identity(PortablePressureIdentityKind.LOGICAL_ENTRY, local)

		logical shouldBe identity(PortablePressureIdentityKind.LOGICAL_ENTRY, local)
		logical shouldNotBe identity(PortablePressureIdentityKind.PHYSICAL_RUN, local)
		logical.value shouldNotContain local
	}

	@Test
	fun `window checksum covers direct statistics quality and wall uncertainty`() {
		val original = window()

		PortablePressureIntegrity.expectedWindowChecksum(original) shouldBe original.contentChecksum
		shouldThrow<IllegalArgumentException> {
			original.copy(wallTimeUncertaintyMs = original.wallTimeUncertaintyMs + 1L)
		}
		shouldThrow<IllegalArgumentException> {
			original.copy(sumSquaredDeviations = original.sumSquaredDeviations + 1.0)
		}
	}

	@Test
	fun `retention only entry has no numeric window and remains explicitly partial`() {
		val run = PortablePressureRunV1(
			identity = identity(PortablePressureIdentityKind.PHYSICAL_RUN, "run"),
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			capturedForWholeRun = true,
			availability = PortablePressureAvailability.NO_RETAINED_OBSERVATION,
			coverage = PortablePressureCoverage.PARTIAL,
			retentionLoss = true,
			windows = emptyList(),
		)
		val entry = PortablePressureEntryV1.create(
			identity = identity(PortablePressureIdentityKind.LOGICAL_ENTRY, "logical"),
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			runs = listOf(run),
		)

		entry.runs.single().windows shouldBe emptyList()
		entry.runs.single().retentionLoss shouldBe true
		shouldThrow<IllegalArgumentException> {
			run.copy(coverage = PortablePressureCoverage.COMPLETE)
		}
	}

	@Test
	fun `numeric windows require retained availability and entry checksum binds membership`() {
		val window = window()
		val run = run(window)
		val entry = PortablePressureEntryV1.create(
			identity = identity(PortablePressureIdentityKind.LOGICAL_ENTRY, "logical"),
			startTimeMs = run.startTimeMs,
			endTimeMs = run.endTimeMs,
			runs = listOf(run),
		)

		PortablePressureIntegrity.expectedEntryChecksum(entry) shouldBe entry.contentChecksum
		shouldThrow<IllegalArgumentException> {
			run.copy(availability = PortablePressureAvailability.UNAVAILABLE)
		}
		shouldThrow<IllegalArgumentException> {
			entry.copy(endTimeMs = entry.endTimeMs + 1L)
		}
	}

	private fun identity(kind: PortablePressureIdentityKind, local: String) =
		PortablePressureOpaqueIdentity.derive(kind, local)

	private fun run(window: PortablePressureWindowV1) = PortablePressureRunV1(
		identity = identity(PortablePressureIdentityKind.PHYSICAL_RUN, "run"),
		startTimeMs = 1_000L,
		endTimeMs = 2_000L,
		capturedForWholeRun = true,
		availability = PortablePressureAvailability.RETAINED,
		coverage = PortablePressureCoverage.COMPLETE,
		retentionLoss = false,
		windows = listOf(window),
	)

	@Suppress("LongMethod")
	private fun window() = PortablePressureWindowV1.create(
		identity = identity(PortablePressureIdentityKind.WINDOW, "fact"),
		intervalStartTimeMs = 1_000L,
		intervalEndTimeMs = 1_150L,
		wallTimeUncertaintyMs = 25L,
		observedDurationNanos = 150_000_000L,
		sampleCount = 4,
		expectedSampleCount = 4,
		meanHectopascals = 1_001.5,
		sumSquaredDeviations = 5.0,
		minimumHectopascals = 1_000f,
		maximumHectopascals = 1_003f,
		firstHectopascals = 1_000f,
		latestHectopascals = 1_003f,
		slopeHectopascalsPerSecond = 15.0,
		rSquared = 1.0,
		sensorAccuracy = PortablePressureSensorAccuracy.HIGH,
		effectiveSamplePeriodMicros = 50_000,
		effectiveMaximumReportLatencyMicros = 0,
		targetWindowDurationNanos = 200_000_000L,
		maximumInterSampleGapNanos = 50_000_000L,
		closure = PortablePressureWindowClosure.TARGET_ELAPSED,
		qualification = PortablePressureWindowQualification.COMPLETE,
		sourceQualityFlags = 0L,
		sourceQualityConfidence = 1f,
		zoneId = "Europe/Prague",
	)
}
