package com.adsamcik.tracker.map.network

import com.adsamcik.tracker.network.FakeNetworkGateway
import com.adsamcik.tracker.network.NetworkPolicy
import com.adsamcik.tracker.network.NetworkPolicyAggregator
import com.adsamcik.tracker.network.NetworkPolicyContribution
import com.adsamcik.tracker.testing.fake.FakeOnlineMapTilesRepository
import com.adsamcik.tracker.shared.preferences.map.OnlineMapTilesState
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Verifies [MapTilesPolicyContributor]:
 *
 *  1. **Unit-level**: preference state → correct contribution
 *     (Inactive when off, Active(correct hosts + bursty rate limit) when on,
 *     custom URL → inferred host, blank URL → empty allowlist).
 *
 *  2. **Integration-level**: end-to-end through the real
 *     [NetworkPolicyAggregator] onto a [FakeNetworkGateway]. This replaces
 *     the deleted `MapStoreOnlineTilesTest` — which asserted MapStore wrote
 *     directly to the gateway, behaviour that no longer exists after the
 *     contributor refactor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("MapTilesPolicyContributor")
class MapTilesPolicyContributorTest {

	@Test
	fun `default-off preference produces Inactive contribution`() = runTest {
		val prefs = FakeOnlineMapTilesRepository()
		val scope = TestScope(StandardTestDispatcher(testScheduler))

		val contributor = MapTilesPolicyContributor(prefs, scope)
		scope.testScheduler.advanceUntilIdle()

		contributor.contribution.value shouldBe NetworkPolicyContribution.Inactive
		contributor.id shouldBe MapTilesPolicyContributor.ID
	}

	@Test
	fun `enabled openfreemap preference produces Active with tiles host`() = runTest {
		val prefs = FakeOnlineMapTilesRepository(
			initialState = OnlineMapTilesState(enabled = true, providerId = "openfreemap"),
		)
		val scope = TestScope(StandardTestDispatcher(testScheduler))

		val contributor = MapTilesPolicyContributor(prefs, scope)
		scope.testScheduler.advanceUntilIdle()

		val c = contributor.contribution.value
		c.shouldBeInstanceOf<NetworkPolicyContribution.Active>()
		c.allowedHosts shouldContainExactlyInAnyOrder setOf("tiles.openfreemap.org")
		c.perHostRateLimit shouldBe MapTilesPolicyContributor.TILE_HOST_RATE_LIMIT_PER_MIN
		c.perHostRateWindowMs shouldBe NetworkPolicy.DEFAULT_RATE_WINDOW_MS
	}

	@Test
	fun `switching provider updates contribution to new host set`() = runTest {
		val prefs = FakeOnlineMapTilesRepository(
			initialState = OnlineMapTilesState(enabled = true, providerId = "openfreemap"),
		)
		val scope = TestScope(StandardTestDispatcher(testScheduler))

		val contributor = MapTilesPolicyContributor(prefs, scope)
		scope.testScheduler.advanceUntilIdle()

		prefs.setProviderId("protomaps")
		scope.testScheduler.advanceUntilIdle()

		val c = contributor.contribution.value
		c.shouldBeInstanceOf<NetworkPolicyContribution.Active>()
		c.allowedHosts shouldContainExactlyInAnyOrder setOf("api.protomaps.com")
	}

	@Test
	fun `custom provider with parseable URL contributes the inferred host`() = runTest {
		val prefs = FakeOnlineMapTilesRepository(
			initialState = OnlineMapTilesState(
				enabled = true,
				providerId = "custom",
				customUrl = "https://tiles.example.com/style.json",
			),
		)
		val scope = TestScope(StandardTestDispatcher(testScheduler))

		val contributor = MapTilesPolicyContributor(prefs, scope)
		scope.testScheduler.advanceUntilIdle()

		val c = contributor.contribution.value
		c.shouldBeInstanceOf<NetworkPolicyContribution.Active>()
		c.allowedHosts shouldContainExactlyInAnyOrder setOf("tiles.example.com")
	}

	@Test
	fun `custom provider with blank URL contributes an empty allowlist (fail-closed)`() = runTest {
		// Empty allowedHosts means EVERY request from this contributor is
		// rejected by the gateway — matches the existing TileProvider.Custom
		// "infer host fails -> empty" contract.
		val prefs = FakeOnlineMapTilesRepository(
			initialState = OnlineMapTilesState(enabled = true, providerId = "custom", customUrl = ""),
		)
		val scope = TestScope(StandardTestDispatcher(testScheduler))

		val contributor = MapTilesPolicyContributor(prefs, scope)
		scope.testScheduler.advanceUntilIdle()

		val c = contributor.contribution.value
		c.shouldBeInstanceOf<NetworkPolicyContribution.Active>()
		c.allowedHosts shouldBe emptySet()
	}

	@Test
	fun `toggling enabled off flips contribution back to Inactive`() = runTest {
		val prefs = FakeOnlineMapTilesRepository(
			initialState = OnlineMapTilesState(enabled = true, providerId = "openfreemap"),
		)
		val scope = TestScope(StandardTestDispatcher(testScheduler))

		val contributor = MapTilesPolicyContributor(prefs, scope)
		scope.testScheduler.advanceUntilIdle()
		contributor.contribution.value.shouldBeInstanceOf<NetworkPolicyContribution.Active>()

		prefs.setEnabled(false)
		scope.testScheduler.advanceUntilIdle()

		contributor.contribution.value shouldBe NetworkPolicyContribution.Inactive
	}

	// --- Integration: contributor → aggregator → gateway --------------------

	@Test
	fun `aggregator drives gateway from contributor on preference flip`() = runTest {
		val prefs = FakeOnlineMapTilesRepository()
		val gateway = FakeNetworkGateway(
			initialEnabled = false,
			initialPolicy = NetworkPolicy.EMPTY,
		)
		val scope = TestScope(StandardTestDispatcher(testScheduler))

		val contributor = MapTilesPolicyContributor(prefs, scope)
		NetworkPolicyAggregator(gateway, setOf(contributor), scope)
		scope.testScheduler.advanceUntilIdle()

		// Default-off preference: gateway disarmed.
		gateway.isEnabled.value shouldBe false
		gateway.policy.value.allowedHosts shouldBe emptySet()

		// User enables online tiles → gateway should arm with the provider hosts.
		prefs.setEnabled(true)
		scope.testScheduler.advanceUntilIdle()

		gateway.isEnabled.value shouldBe true
		gateway.policy.value.allowedHosts shouldContainExactlyInAnyOrder setOf("tiles.openfreemap.org")
		gateway.policy.value.perHostRateLimit shouldBe
			MapTilesPolicyContributor.TILE_HOST_RATE_LIMIT_PER_MIN

		// User switches provider → gateway should re-arm with new hosts.
		prefs.setProviderId("protomaps")
		scope.testScheduler.advanceUntilIdle()

		gateway.policy.value.allowedHosts shouldContainExactlyInAnyOrder setOf("api.protomaps.com")

		// User disables → gateway should disarm and clear the allowlist.
		prefs.setEnabled(false)
		scope.testScheduler.advanceUntilIdle()

		gateway.isEnabled.value shouldBe false
		gateway.policy.value.allowedHosts shouldBe emptySet()
	}
}
