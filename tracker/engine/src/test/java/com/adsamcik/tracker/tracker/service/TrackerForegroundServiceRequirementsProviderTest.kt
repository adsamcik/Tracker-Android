package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.api.TrackerForegroundServiceRequirements
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrackerForegroundServiceRequirementsProviderTest {
	@Test
	fun `every individual source maps to its foreground category`() {
		val cases = listOf(
			noSources().copy(locationEnabled = true) to requirements(location = true),
			noSources().copy(activityEnabled = true) to requirements(health = true),
			noSources().copy(stepsEnabled = true) to requirements(health = true),
			noSources().copy(wifiEnabled = true) to requirements(signal = true),
			noSources().copy(cellEnabled = true) to requirements(signal = true),
			noSources().copy(barometerEnabled = true) to requirements(signal = true),
		)

		cases.forEach { (params, expected) -> resolve(params) shouldBe expected }
	}

	@Test
	fun `combined sources retain all categories for later permission transitions`() {
		resolve(
			noSources().copy(
				locationEnabled = true,
				activityEnabled = true,
				wifiEnabled = true,
			),
		) shouldBe requirements(location = true, health = true, signal = true)
	}

	@Test
	fun `automatic location start requires effective background location`() {
		val params = noSources().copy(locationEnabled = true)

		resolve(
			params = params,
			isUserInitiated = false,
			backgroundLocationAvailable = false,
		) shouldBe null
		resolve(
			params = params,
			isUserInitiated = false,
			backgroundLocationAvailable = true,
		) shouldBe requirements(location = true)
	}

	@Test
	fun `automatic location plan does not degrade to signals when location is revoked`() {
		resolve(
			params = noSources().copy(locationEnabled = true, wifiEnabled = true),
			isUserInitiated = false,
			locationAvailable = false,
			backgroundLocationAvailable = false,
		) shouldBe null
	}

	@Test
	fun `manual plan may continue with a real signal source when location is unavailable`() {
		resolve(
			params = noSources().copy(locationEnabled = true, barometerEnabled = true),
			locationAvailable = false,
		) shouldBe requirements(signal = true)
	}

	@Test
	fun `ambient mode suppresses location and keeps available health sources`() {
		resolve(
			params = noSources().copy(locationEnabled = true, stepsEnabled = true),
			isUserInitiated = false,
			isAmbient = true,
		) shouldBe requirements(health = true)

		resolve(
			params = noSources().copy(locationEnabled = true),
			isUserInitiated = false,
			isAmbient = true,
		) shouldBe null
	}

	@Test
	fun `activity permission revocation narrows health plus signal to signal`() {
		val params = noSources().copy(activityEnabled = true, cellEnabled = true)

		resolve(params) shouldBe requirements(health = true, signal = true)
		resolve(
			params = params,
			activityAvailable = false,
		) shouldBe requirements(signal = true)
	}

	@Test
	fun `no configured available source rejects the start`() {
		resolve(noSources()) shouldBe null
	}

	private fun resolve(
		params: TrackingParamsState,
		isUserInitiated: Boolean = true,
		isAmbient: Boolean = false,
		locationAvailable: Boolean = true,
		backgroundLocationAvailable: Boolean = true,
		activityAvailable: Boolean = true,
		stepsAvailable: Boolean = true,
		wifiAvailable: Boolean = true,
		cellAvailable: Boolean = true,
		barometerAvailable: Boolean = true,
	): TrackerForegroundServiceRequirements? = resolveTrackerForegroundServiceRequirements(
		params = params,
		isUserInitiated = isUserInitiated,
		isAmbient = isAmbient,
		locationAvailable = locationAvailable,
		backgroundLocationAvailable = backgroundLocationAvailable,
		activityAvailable = activityAvailable,
		stepsAvailable = stepsAvailable,
		wifiAvailable = wifiAvailable,
		cellAvailable = cellAvailable,
		barometerAvailable = barometerAvailable,
	)

	private fun requirements(
		location: Boolean = false,
		health: Boolean = false,
		signal: Boolean = false,
	) = TrackerForegroundServiceRequirements(location, health, signal)

	private fun noSources() = TrackingParamsState(
		locationEnabled = false,
		activityEnabled = false,
		stepsEnabled = false,
		wifiEnabled = false,
		cellEnabled = false,
		barometerEnabled = false,
	)
}
