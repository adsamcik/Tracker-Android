package com.adsamcik.tracker.shared.base.database

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test

class ActivityCapturedPortableFormatV1Test {
	@Test
	fun `opaque identities are stable and kind namespaced`() {
		val logical = PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.LOGICAL_ENTRY,
			"private-local-id",
		)
		val run = PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.PHYSICAL_RUN,
			"private-local-id",
		)

		logical shouldBe PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.LOGICAL_ENTRY,
			"private-local-id",
		)
		(logical == run) shouldBe false
		logical.value.length shouldBe 64
		(logical.value.contains("private-local-id")) shouldBe false
	}

	@Test
	fun `window checksum binds redacted semantic content`() {
		val window = completeWindow()

		shouldThrow<IllegalArgumentException> {
			window.copy(storedZoneId = "UTC")
		}
	}

	@Test
	fun `coverage cannot turn an explicit gap into complete or numeric history`() {
		val identity = PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.CAPTURE_WINDOW,
			"gap-window",
		)
		val gap = PortableActivityFragmentV1.Gap(0L, 100L, "NO_QUALIFIED_EVIDENCE")
		val invalidChecksum = ActivityCapturedPortableIntegrity.windowChecksum(
			identity = identity,
			startOffsetNanos = 0L,
			endOffsetNanos = 100L,
			storedZoneId = "UTC",
			coverage = PortableActivityWindowCoverage.COMPLETE,
			knownActiveDurationNanos = 0L,
			knownInactiveDurationNanos = 0L,
			unknownActivityDurationNanos = 0L,
			unobservedDurationNanos = 100L,
			fragments = listOf(gap),
		)

		shouldThrow<IllegalArgumentException> {
			PortableActivityWindowV1(
				identity,
				invalidChecksum,
				0L,
				100L,
				"UTC",
				PortableActivityWindowCoverage.COMPLETE,
				0L,
				0L,
				0L,
				100L,
				listOf(gap),
			)
		}
	}

	private fun completeWindow(): PortableActivityWindowV1 {
		val identity = PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.CAPTURE_WINDOW,
			"complete-window",
		)
		val fragments = listOf(
			PortableActivityFragmentV1.Band(
				startOffsetNanos = 0L,
				endOffsetNanos = 100L,
				activity = "WALKING",
				mechanism = "TRANSITION",
				refinedTransitionActivity = null,
				confidenceKind = "TRANSITION_SIGNAL",
				confidenceMinimumPercent = null,
				confidenceMaximumPercent = null,
				confidenceObservationCount = null,
				startWallTimeMs = 1_000L,
				startWallTimeUncertaintyMs = 10L,
				startBoundaryKind = "EXACT_PROVIDER_OBSERVATION",
				endWallTimeMs = 1_001L,
				endWallTimeUncertaintyMs = 11L,
				endBoundaryKind = "SAME_CLOCK_EXTRAPOLATION",
				wallTimeContinuity = "SAME_ANCHOR",
			),
		)
		val checksum = ActivityCapturedPortableIntegrity.windowChecksum(
			identity = identity,
			startOffsetNanos = 0L,
			endOffsetNanos = 100L,
			storedZoneId = "Europe/Prague",
			coverage = PortableActivityWindowCoverage.COMPLETE,
			knownActiveDurationNanos = 100L,
			knownInactiveDurationNanos = 0L,
			unknownActivityDurationNanos = 0L,
			unobservedDurationNanos = 0L,
			fragments = fragments,
		)
		return PortableActivityWindowV1(
			identity,
			checksum,
			0L,
			100L,
			"Europe/Prague",
			PortableActivityWindowCoverage.COMPLETE,
			100L,
			0L,
			0L,
			0L,
			fragments,
		)
	}
}
