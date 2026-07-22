package com.adsamcik.tracker.shared.base.data

import com.adsamcik.tracker.shared.model.AltitudeDatum
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for [MutableCollectionData.distanceFromPreviousM].
 *
 * This field carries the bridged distance-from-last-accepted-location produced by the location
 * filtering component and consumed by the session tracker. It must distinguish "not set" (null,
 * location rejected) from an explicit `0f` (first accepted fix), otherwise the session would either
 * silently lose or double-count distance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MutableCollectionDataTest {

	@Test
	fun `defaults to null when never set`() {
		MutableCollectionData(1_000L).distanceFromPreviousM.shouldBeNull()
	}

	@Test
	fun `returns the value that was set`() {
		val data = MutableCollectionData(1_000L)
		data.distanceFromPreviousM = 123.5f
		data.distanceFromPreviousM shouldBe 123.5f
	}

	@Test
	fun `distinguishes an explicit zero from unset`() {
		val data = MutableCollectionData(1_000L)
		data.distanceFromPreviousM = 0f
		// Must be 0f, NOT null — the first accepted fix legitimately contributes zero distance.
		data.distanceFromPreviousM shouldBe 0f
	}

	@Test
	fun `setting null clears a previously set value`() {
		val data = MutableCollectionData(1_000L)
		data.distanceFromPreviousM = 50f
		data.distanceFromPreviousM = null
		data.distanceFromPreviousM.shouldBeNull()
	}

	@Test
	fun `is independent of the location field`() {
		val data = MutableCollectionData(1_000L)
		data.distanceFromPreviousM = 42f
		// distanceFromPreviousM is a separate bundle entry and must not affect location.
		data.location.shouldBeNull()
		data.distanceFromPreviousM shouldBe 42f
	}

	@Test
	fun `exposes only Android model MSL processed altitude to presentation consumers`() {
		val data = MutableCollectionData(1_000L)
		data.processedAltitude = ProcessedAltitudeData(
			altitudeM = 420f,
			datum = AltitudeDatum.ANDROID_MODEL_MSL,
		)
		data.androidModelMslAltitudeM shouldBe 420f

		data.processedAltitude = ProcessedAltitudeData(
			altitudeM = 500f,
			datum = AltitudeDatum.WGS84_ELLIPSOID,
		)
		data.androidModelMslAltitudeM.shouldBeNull()
	}
}
