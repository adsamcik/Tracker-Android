package com.adsamcik.tracker.map.presentation

import androidx.lifecycle.SavedStateHandle
import com.adsamcik.tracker.network.FakeNetworkGateway
import com.adsamcik.tracker.network.NetworkPolicy
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.preferences.map.OnlineMapTilesState
import com.adsamcik.tracker.testing.fake.FakeOnlineMapTilesRepository
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies that `MapStore` mirrors the user's online-tile preference into the
 * `NetworkGateway` kill-switch + allowlist. This is the security pivot for
 * online tiles: even if a future consumer routes traffic through the gateway,
 * the user's opt-out always wins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapStoreOnlineTilesTest {

	private val mockTrackerController: TrackerServiceController = mockk(relaxed = true)
	private val testDispatcher = StandardTestDispatcher()

	private lateinit var prefs: FakeOnlineMapTilesRepository
	private lateinit var gateway: FakeNetworkGateway

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
		prefs = FakeOnlineMapTilesRepository()
		// Start the gateway in an "armed-on" state to assert it gets disarmed
		// on first emit (the default preference is enabled=false).
		gateway = FakeNetworkGateway(
			initialEnabled = true,
			initialPolicy = NetworkPolicy(allowedHosts = setOf("leftover.example.com")),
		)
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	private fun store() = MapStore(
		SavedStateHandle(),
		mockTrackerController,
		TestDispatchersProvider(testDispatcher),
		prefs,
		gateway,
	)

	@Test
	fun `gateway is disarmed when preference defaults to off`() = runTest {
		store()
		testDispatcher.scheduler.advanceUntilIdle()

		gateway.isEnabled.value shouldBe false
		gateway.policy.value.allowedHosts shouldBe emptySet()
	}

	@Test
	fun `gateway is armed with the openfreemap allowlist when enabled`() = runTest {
		store()
		testDispatcher.scheduler.advanceUntilIdle()
		prefs.setEnabled(true)
		testDispatcher.scheduler.advanceUntilIdle()

		gateway.isEnabled.value shouldBe true
		gateway.policy.value.allowedHosts shouldContainExactly setOf("tiles.openfreemap.org")
	}

	@Test
	fun `switching provider re-arms the gateway with the new allowlist`() = runTest {
		store()
		testDispatcher.scheduler.advanceUntilIdle()
		prefs.setEnabled(true)
		testDispatcher.scheduler.advanceUntilIdle()
		prefs.setProviderId("protomaps")
		testDispatcher.scheduler.advanceUntilIdle()

		gateway.isEnabled.value shouldBe true
		gateway.policy.value.allowedHosts shouldContainExactly setOf("api.protomaps.com")
	}

	@Test
	fun `custom provider with a parseable url arms the inferred host`() = runTest {
		prefs.setState(
			OnlineMapTilesState(
				enabled = true,
				providerId = "custom",
				customUrl = "https://tiles.example.com/style.json",
			)
		)
		store()
		testDispatcher.scheduler.advanceUntilIdle()

		gateway.isEnabled.value shouldBe true
		gateway.policy.value.allowedHosts shouldContainExactly setOf("tiles.example.com")
	}

	@Test
	fun `custom provider with a blank url arms an empty allowlist`() = runTest {
		// Fail-closed: empty allowlist means every request is rejected.
		prefs.setState(
			OnlineMapTilesState(
				enabled = true,
				providerId = "custom",
				customUrl = "",
			)
		)
		store()
		testDispatcher.scheduler.advanceUntilIdle()

		gateway.isEnabled.value shouldBe true
		gateway.policy.value.allowedHosts shouldBe emptySet()
	}

	@Test
	fun `toggling enabled off disarms the gateway and clears the allowlist`() = runTest {
		prefs.setState(OnlineMapTilesState(enabled = true, providerId = "openfreemap"))
		store()
		testDispatcher.scheduler.advanceUntilIdle()
		// sanity: armed
		gateway.isEnabled.value shouldBe true

		prefs.setEnabled(false)
		testDispatcher.scheduler.advanceUntilIdle()

		gateway.isEnabled.value shouldBe false
		gateway.policy.value.allowedHosts shouldBe emptySet()
	}
}
