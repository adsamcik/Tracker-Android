package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose as CanonicalTrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource as CanonicalTrackingSource
import com.adsamcik.tracker.shared.preferences.tracking.SourcePurpose
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrackingSourcePurposeBridgeTest {
	@Test
	fun `tracker API and SourcePolicy consume the same exhaustive source purpose matrix`() {
		TrackingPurpose.entries shouldBe SourcePurpose.entries
		TrackingCaptureSource.entries.forEach { apiSource ->
			val canonicalSource = apiSource.toCanonicalTrackingSource()
			TrackingSourceComponent.fromStableCode(canonicalSource.stableCode) shouldBe
				canonicalSource
			canonicalSource.toApiTrackingSource() shouldBe apiSource

			TrackingPurpose.entries.forEach { purpose ->
				apiSource.supportsPurpose(purpose) shouldBe canonicalSource.supports(purpose)
				if (canonicalSource.supports(purpose)) {
					val canonicalIdentity = canonicalSource.forPurpose(purpose)
					apiSource.forPurpose(purpose).canonicalIdentity shouldBe canonicalIdentity
					TrackingSourcePurposeIdentity.from(canonicalIdentity) shouldBe
						apiSource.forPurpose(purpose)
				} else {
					shouldThrow<IllegalArgumentException> {
						apiSource.forPurpose(purpose)
					}
					shouldThrow<IllegalArgumentException> {
						canonicalSource.forPurpose(purpose)
					}
				}
			}
		}
	}

	@Test
	fun `unknown stable source and purpose conversions fail`() {
		shouldThrow<IllegalArgumentException> {
			CanonicalTrackingSource.fromStableCode(Int.MIN_VALUE)
		}
		shouldThrow<IllegalArgumentException> {
			CanonicalTrackingPurpose.fromStableName("UNKNOWN")
		}
	}
}
