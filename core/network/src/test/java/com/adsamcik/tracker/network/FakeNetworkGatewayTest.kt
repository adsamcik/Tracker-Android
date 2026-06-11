package com.adsamcik.tracker.network

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("FakeNetworkGateway contract")
class FakeNetworkGatewayTest {

	@Test
	fun `request to allowed host with stubbed response returns success`() = runTest {
		val gateway = FakeNetworkGateway(
			initialPolicy = NetworkPolicy(allowedHosts = setOf("tiles.openfreemap.org")),
		)
		gateway.respondOk("https://tiles.openfreemap.org/", "tile-bytes".encodeToByteArray())

		val response = gateway.request(NetworkRequest("https://tiles.openfreemap.org/styles/liberty"))

		response.shouldBeInstanceOf<NetworkResponse.Success>()
		response.statusCode shouldBe 200
		response.body.decodeToString() shouldBe "tile-bytes"
		gateway.recordedRequests.size shouldBe 1
	}

	@Test
	fun `kill switch immediately rejects with GatewayDisabled`() = runTest {
		val gateway = FakeNetworkGateway(initialEnabled = false)
		val response = gateway.request(NetworkRequest("https://example.com/x"))

		response.shouldBeInstanceOf<NetworkResponse.Failure>()
		response.error shouldBe NetworkError.GatewayDisabled
	}

	@Test
	fun `host not in allowlist returns HostNotAllowed`() = runTest {
		val gateway = FakeNetworkGateway(
			initialPolicy = NetworkPolicy(allowedHosts = setOf("tiles.openfreemap.org")),
		)
		val response = gateway.request(NetworkRequest("https://evil.example/leak"))

		response.shouldBeInstanceOf<NetworkResponse.Failure>()
		(response.error as NetworkError.HostNotAllowed).host shouldBe "evil.example"
	}

	@Test
	fun `subdomains are NOT auto-allowed`() = runTest {
		val gateway = FakeNetworkGateway(
			initialPolicy = NetworkPolicy(allowedHosts = setOf("openfreemap.org")),
		)
		val response = gateway.request(NetworkRequest("https://tiles.openfreemap.org/styles/liberty"))

		response.shouldBeInstanceOf<NetworkResponse.Failure>()
		(response.error as NetworkError.HostNotAllowed).host shouldBe "tiles.openfreemap.org"
	}

	@Test
	fun `setPolicy hot-swaps the allowlist`() = runTest {
		val gateway = FakeNetworkGateway(
			initialPolicy = NetworkPolicy(allowedHosts = setOf("a.example")),
		)
		gateway.respondOk("https://b.example/")

		// Initial policy blocks b.example
		val first = gateway.request(NetworkRequest("https://b.example/x"))
		first.shouldBeInstanceOf<NetworkResponse.Failure>()

		// Hot-swap to a policy that allows b.example
		gateway.setPolicy(NetworkPolicy(allowedHosts = setOf("b.example")))
		val second = gateway.request(NetworkRequest("https://b.example/x"))
		second.shouldBeInstanceOf<NetworkResponse.Success>()
	}

	@Test
	fun `default policy denies everything`() {
		val empty = NetworkPolicy.EMPTY
		empty.allows("anywhere.example") shouldBe false
		empty.allows("") shouldBe false
	}

	@Test
	fun `allowed host matching is case-insensitive and trim-resilient`() = runTest {
		val gateway = FakeNetworkGateway(
			initialPolicy = NetworkPolicy(allowedHosts = setOf("  TILES.OpenFreeMap.org  ")),
		)
		gateway.respondOk("https://tiles.openfreemap.org/")
		val response = gateway.request(NetworkRequest("https://tiles.openfreemap.org/x"))
		response.shouldBeInstanceOf<NetworkResponse.Success>()
	}

	@Test
	fun `invalid URL returns InvalidUrl error`() = runTest {
		val gateway = FakeNetworkGateway()
		val response = gateway.request(NetworkRequest("not-a-valid-url"))
		response.shouldBeInstanceOf<NetworkResponse.Failure>()
		response.error.shouldBeInstanceOf<NetworkError.InvalidUrl>()
	}
}
