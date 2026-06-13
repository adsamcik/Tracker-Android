package com.adsamcik.tracker.shared.base.data

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * Regression tests for [MutableCollectionData.distanceFromPreviousM].
 *
 * This field carries the bridged distance-from-last-accepted-location produced by the location
 * filtering component and consumed by the session tracker. It must distinguish "not set" (null,
 * location rejected) from an explicit `0f` (first accepted fix), otherwise the session would either
 * silently lose or double-count distance.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
@DisplayName("MutableCollectionData.distanceFromPreviousM")
class MutableCollectionDataTest {

	@Test
	@DisplayName("defaults to null when never set")
	fun defaultsToNull() {
		MutableCollectionData(1_000L).distanceFromPreviousM.shouldBeNull()
	}

	@Test
	@DisplayName("returns the value that was set")
	fun returnsSetValue() {
		val data = MutableCollectionData(1_000L)
		data.distanceFromPreviousM = 123.5f
		data.distanceFromPreviousM shouldBe 123.5f
	}

	@Test
	@DisplayName("distinguishes an explicit zero from unset")
	fun distinguishesZeroFromUnset() {
		val data = MutableCollectionData(1_000L)
		data.distanceFromPreviousM = 0f
		// Must be 0f, NOT null — the first accepted fix legitimately contributes zero distance.
		data.distanceFromPreviousM shouldBe 0f
	}

	@Test
	@DisplayName("setting null clears a previously set value")
	fun settingNullClears() {
		val data = MutableCollectionData(1_000L)
		data.distanceFromPreviousM = 50f
		data.distanceFromPreviousM = null
		data.distanceFromPreviousM.shouldBeNull()
	}

	@Test
	@DisplayName("is independent of the location field")
	fun independentOfLocation() {
		val data = MutableCollectionData(1_000L)
		data.distanceFromPreviousM = 42f
		// distanceFromPreviousM is a separate bundle entry and must not affect location.
		data.location.shouldBeNull()
		data.distanceFromPreviousM shouldBe 42f
	}
}
