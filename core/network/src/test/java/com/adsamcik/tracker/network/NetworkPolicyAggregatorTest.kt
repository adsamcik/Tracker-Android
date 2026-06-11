package com.adsamcik.tracker.network

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import dagger.multibindings.Multibinds

/**
 * Verifies the composition semantics of [NetworkPolicyAggregator]:
 *
 *  - kill switch is armed iff at least one contributor is Active
 *  - effective allowlist is the union of every Active contributor
 *  - effective rate limit is the MAX requested by any Active contributor
 *  - hot-swap (Active → Inactive) immediately disarms the gateway
 *  - zero registered contributors leaves the gateway at its safe default
 *
 * Tests use [FakeNetworkGateway] (which records every setEnabled / setPolicy
 * call into its observable [NetworkGateway.isEnabled] + [NetworkGateway.policy]
 * StateFlows) and a tiny inline fake contributor backed by a [MutableStateFlow]
 * so we can simulate preference / lifecycle changes deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("NetworkPolicyAggregator composition")
class NetworkPolicyAggregatorTest {

	@Test
	fun `network module owns contributor contract and empty multibinds declaration`() {
		NetworkPolicyContributor::class.java.packageName shouldBe "com.adsamcik.tracker.network"
		NetworkPolicyContributorBindingsModule::class.java.packageName shouldBe "com.adsamcik.tracker.network"
		NetworkPolicyContributorBindingsModule::class.java
			.getDeclaredMethod("bindNetworkPolicyContributors")
			.getAnnotation(Multibinds::class.java) shouldNotBe null
	}

	private class FakeContributor(
		override val id: String,
		initial: NetworkPolicyContribution = NetworkPolicyContribution.Inactive,
	) : NetworkPolicyContributor {
		private val _contribution = MutableStateFlow(initial)
		override val contribution: StateFlow<NetworkPolicyContribution> = _contribution.asStateFlow()
		fun emit(next: NetworkPolicyContribution) {
			_contribution.value = next
		}
	}

	@Test
	fun `all contributors inactive leaves gateway disabled with empty policy`() = runTest {
		val gateway = FakeNetworkGateway(
			initialEnabled = true,
			initialPolicy = NetworkPolicy(allowedHosts = setOf("leftover.example")),
		)
		val a = FakeContributor("a")
		val b = FakeContributor("b")
		val scope = TestScope(StandardTestDispatcher(testScheduler))

		NetworkPolicyAggregator(gateway, setOf(a, b), scope)
		scope.testScheduler.advanceUntilIdle()

		gateway.isEnabled.value shouldBe false
		gateway.policy.value.allowedHosts shouldBe emptySet()
	}

	@Test
	fun `single active contributor arms the gateway with its allowlist`() = runTest {
		val gateway = FakeNetworkGateway(initialEnabled = false)
		val a = FakeContributor(
			id = "map-tiles",
			initial = NetworkPolicyContribution.Active(
				allowedHosts = setOf("tiles.openfreemap.org"),
				perHostRateLimit = 3600,
			),
		)
		val scope = TestScope(StandardTestDispatcher(testScheduler))

		NetworkPolicyAggregator(gateway, setOf(a), scope)
		scope.testScheduler.advanceUntilIdle()

		gateway.isEnabled.value shouldBe true
		gateway.policy.value.allowedHosts shouldContainExactlyInAnyOrder setOf("tiles.openfreemap.org")
		gateway.policy.value.perHostRateLimit shouldBe 3600
	}

	@Test
	fun `two active contributors with disjoint hosts produce the union`() = runTest {
		val gateway = FakeNetworkGateway(initialEnabled = false)
		val map = FakeContributor(
			id = "map-tiles",
			initial = NetworkPolicyContribution.Active(
				allowedHosts = setOf("tiles.openfreemap.org"),
				perHostRateLimit = 3600,
				perHostRateWindowMs = 60_000L,
			),
		)
		val download = FakeContributor(
			id = "osm-download",
			initial = NetworkPolicyContribution.Active(
				allowedHosts = setOf("download.protomaps.com"),
				perHostRateLimit = 480,
				perHostRateWindowMs = 60_000L,
			),
		)
		val scope = TestScope(StandardTestDispatcher(testScheduler))

		NetworkPolicyAggregator(gateway, setOf(map, download), scope)
		scope.testScheduler.advanceUntilIdle()

		gateway.isEnabled.value shouldBe true
		gateway.policy.value.allowedHosts shouldContainExactlyInAnyOrder setOf(
			"tiles.openfreemap.org",
			"download.protomaps.com",
		)
		// MAX wins so the bursty contributor isn't capped by a quieter peer.
		gateway.policy.value.perHostRateLimit shouldBe 3600
	}

	@Test
	fun `two active contributors with overlapping hosts deduplicate to the union`() = runTest {
		val gateway = FakeNetworkGateway(initialEnabled = false)
		val a = FakeContributor(
			id = "a",
			initial = NetworkPolicyContribution.Active(
				allowedHosts = setOf("shared.example", "a-only.example"),
				perHostRateLimit = 120,
			),
		)
		val b = FakeContributor(
			id = "b",
			initial = NetworkPolicyContribution.Active(
				allowedHosts = setOf("shared.example", "b-only.example"),
				perHostRateLimit = 60,
			),
		)
		val scope = TestScope(StandardTestDispatcher(testScheduler))

		NetworkPolicyAggregator(gateway, setOf(a, b), scope)
		scope.testScheduler.advanceUntilIdle()

		gateway.policy.value.allowedHosts shouldContainExactlyInAnyOrder setOf(
			"shared.example",
			"a-only.example",
			"b-only.example",
		)
		gateway.policy.value.perHostRateLimit shouldBe 120
	}

	@Test
	fun `take MAX of perHostRateWindowMs across active contributors`() = runTest {
		val gateway = FakeNetworkGateway(initialEnabled = false)
		val short = FakeContributor(
			id = "short-window",
			initial = NetworkPolicyContribution.Active(
				allowedHosts = setOf("a.example"),
				perHostRateLimit = 60,
				perHostRateWindowMs = 30_000L,
			),
		)
		val long = FakeContributor(
			id = "long-window",
			initial = NetworkPolicyContribution.Active(
				allowedHosts = setOf("b.example"),
				perHostRateLimit = 60,
				perHostRateWindowMs = 120_000L,
			),
		)
		val scope = TestScope(StandardTestDispatcher(testScheduler))

		NetworkPolicyAggregator(gateway, setOf(short, long), scope)
		scope.testScheduler.advanceUntilIdle()

		gateway.policy.value.perHostRateWindowMs shouldBe 120_000L
	}

	@Test
	fun `hot-swap active to inactive disarms the gateway`() = runTest {
		val gateway = FakeNetworkGateway(initialEnabled = false)
		val a = FakeContributor(
			id = "feature",
			initial = NetworkPolicyContribution.Active(
				allowedHosts = setOf("active.example"),
				perHostRateLimit = 60,
			),
		)
		val scope = TestScope(StandardTestDispatcher(testScheduler))

		NetworkPolicyAggregator(gateway, setOf(a), scope)
		scope.testScheduler.advanceUntilIdle()
		gateway.isEnabled.value shouldBe true

		a.emit(NetworkPolicyContribution.Inactive)
		scope.testScheduler.advanceUntilIdle()

		gateway.isEnabled.value shouldBe false
		gateway.policy.value.allowedHosts shouldBe emptySet()
	}

	@Test
	fun `partial-active hot-swap keeps only remaining active contributor's hosts`() = runTest {
		val gateway = FakeNetworkGateway(initialEnabled = false)
		val map = FakeContributor(
			id = "map-tiles",
			initial = NetworkPolicyContribution.Active(
				allowedHosts = setOf("tiles.example"),
				perHostRateLimit = 3600,
			),
		)
		val download = FakeContributor(
			id = "osm-download",
			initial = NetworkPolicyContribution.Active(
				allowedHosts = setOf("download.example"),
				perHostRateLimit = 480,
			),
		)
		val scope = TestScope(StandardTestDispatcher(testScheduler))

		NetworkPolicyAggregator(gateway, setOf(map, download), scope)
		scope.testScheduler.advanceUntilIdle()

		// Download finishes — contributor goes Inactive. Map tiles stay on.
		download.emit(NetworkPolicyContribution.Inactive)
		scope.testScheduler.advanceUntilIdle()

		gateway.isEnabled.value shouldBe true
		gateway.policy.value.allowedHosts shouldContainExactlyInAnyOrder setOf("tiles.example")
		gateway.policy.value.perHostRateLimit shouldBe 3600
	}

	@Test
	fun `zero contributors registered leaves gateway at its initial deny-all state`() = runTest {
		// Degenerate case: a build configuration that binds no contributors.
		// combine() over an empty list never emits, so the aggregator
		// short-circuits and the gateway keeps whatever state it was
		// constructed with. Production DefaultNetworkGateway defaults to
		// deny-all (kill switch off, EMPTY policy) — we mirror that here on
		// the fake so the test reflects production behaviour, not the fake's
		// own demo-friendly default policy.
		val gateway = FakeNetworkGateway(
			initialEnabled = false,
			initialPolicy = NetworkPolicy.EMPTY,
		)
		val scope = TestScope(StandardTestDispatcher(testScheduler))

		val aggregator = NetworkPolicyAggregator(gateway, emptySet(), scope)
		scope.testScheduler.advanceUntilIdle()

		aggregator.isCollecting shouldBe false
		gateway.isEnabled.value shouldBe false
		gateway.policy.value shouldBe NetworkPolicy.EMPTY
	}

	@Test
	fun `inactive to active hot-swap arms the gateway with newly required hosts`() = runTest {
		val gateway = FakeNetworkGateway(initialEnabled = false)
		val download = FakeContributor(
			id = "osm-download",
			initial = NetworkPolicyContribution.Inactive,
		)
		val scope = TestScope(StandardTestDispatcher(testScheduler))

		NetworkPolicyAggregator(gateway, setOf(download), scope)
		scope.testScheduler.advanceUntilIdle()
		gateway.isEnabled.value shouldBe false

		download.emit(
			NetworkPolicyContribution.Active(
				allowedHosts = setOf("download.protomaps.com"),
				perHostRateLimit = 480,
			),
		)
		scope.testScheduler.advanceUntilIdle()

		gateway.isEnabled.value shouldBe true
		gateway.policy.value.allowedHosts shouldContainExactlyInAnyOrder setOf("download.protomaps.com")
	}
}
