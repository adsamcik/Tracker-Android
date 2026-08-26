package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionSettings
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import org.junit.Test

class TrackingSessionOwnershipStressTest {
	@Test
	fun `randomized source settings resolve only to event ownership`() {
		val random = Random(8_2026)
		repeat(1_000) { revision ->
			val rollout = TrackingRolloutState.eventCanonical(
				sources = SourceKind.entries.toSet(),
				revision = revision.toLong(),
			)
			val frequencies = SourceCollectionSettings(
				location = random.frequency(),
				activity = random.frequency(),
				steps = random.frequency(),
				pressure = random.frequency(),
				wifi = random.frequency(),
				cell = random.frequency(),
			)
			val settings = TrackingParamsState(
				locationEnabled = frequencies.location != SourceCollectionFrequency.OFF,
				activityEnabled = frequencies.activity != SourceCollectionFrequency.OFF,
				stepsEnabled = frequencies.steps != SourceCollectionFrequency.OFF,
				barometerEnabled = frequencies.pressure != SourceCollectionFrequency.OFF,
				wifiEnabled = frequencies.wifi != SourceCollectionFrequency.OFF,
				cellEnabled = frequencies.cell != SourceCollectionFrequency.OFF,
				sourceCollectionSettings = frequencies,
			)
			val result = TrackingSessionOwnership.resolve(rollout, settings)
			val expectedEnabled = buildSet {
				if (settings.locationEnabled) add(SourceKind.LOCATION)
				if (settings.activityEnabled) add(SourceKind.ACTIVITY)
				if (settings.stepsEnabled) add(SourceKind.STEPS)
				if (settings.barometerEnabled) add(SourceKind.PRESSURE)
				if (settings.wifiEnabled) add(SourceKind.WIFI)
				if (settings.cellEnabled) add(SourceKind.CELL)
			}

			result.enabledEventSources shouldBe expectedEnabled
			result.configuredSources shouldBe expectedEnabled
			result.containedSources shouldBe emptySet()
		}
	}

	@Test
	fun `legacy ownership is rejected after retirement`() {
		shouldThrow<IllegalArgumentException> {
			TrackingSessionOwnership.resolve(TrackingRolloutState.legacy(), TrackingParamsState())
		}
	}

	@Test
	fun `disabled legacy source does not block an independently event-owned source`() {
		val rollout = TrackingRolloutState.eventCanonical(setOf(SourceKind.STEPS))
		val settings = TrackingParamsState(
			locationEnabled = false,
			activityEnabled = false,
			stepsEnabled = true,
			barometerEnabled = false,
			sourceCollectionSettings = SourceCollectionSettings(
				location = SourceCollectionFrequency.OFF,
				activity = SourceCollectionFrequency.OFF,
				steps = SourceCollectionFrequency.BALANCED,
				pressure = SourceCollectionFrequency.OFF,
			),
		)

		TrackingSessionOwnership.resolve(rollout, settings).enabledEventSources shouldBe
			setOf(SourceKind.STEPS)
	}

	@Test
	fun `contained sibling degrades without blocking a reachable source`() {
		val rollout = TrackingRolloutState.eventCanonical(setOf(SourceKind.STEPS))
		val settings = TrackingParamsState(
			locationEnabled = true,
			activityEnabled = false,
			stepsEnabled = true,
			barometerEnabled = false,
			sourceCollectionSettings = SourceCollectionSettings(
				location = SourceCollectionFrequency.BALANCED,
				activity = SourceCollectionFrequency.OFF,
				steps = SourceCollectionFrequency.BALANCED,
				pressure = SourceCollectionFrequency.OFF,
			),
		)

		val ownership = TrackingSessionOwnership.resolve(rollout, settings)

		ownership.configuredSources shouldBe setOf(SourceKind.LOCATION, SourceKind.STEPS)
		ownership.enabledEventSources shouldBe setOf(SourceKind.STEPS)
		ownership.containedSources shouldBe setOf(SourceKind.LOCATION)
		ownership.isPartiallyAccepted shouldBe true
	}

	@Test
	fun `zero reachable sources remain explicit for the start boundary to reject`() {
		val ownership = TrackingSessionOwnership.resolve(
			TrackingRolloutState.contained(),
			TrackingParamsState(
				locationEnabled = true,
				activityEnabled = false,
				stepsEnabled = false,
				barometerEnabled = false,
				wifiEnabled = false,
				cellEnabled = false,
				sourceCollectionSettings = SourceCollectionSettings(
					location = SourceCollectionFrequency.BALANCED,
					activity = SourceCollectionFrequency.OFF,
					steps = SourceCollectionFrequency.OFF,
					pressure = SourceCollectionFrequency.OFF,
					wifi = SourceCollectionFrequency.OFF,
					cell = SourceCollectionFrequency.OFF,
				),
			),
		)

		ownership.enabledEventSources shouldBe emptySet()
		ownership.containedSources shouldBe setOf(SourceKind.LOCATION)
		ownership.eventCoordinatorRequired shouldBe false
	}
}

private fun Random.frequency(): SourceCollectionFrequency = SourceCollectionFrequency.entries[nextInt(
	SourceCollectionFrequency.entries.size,
)]
