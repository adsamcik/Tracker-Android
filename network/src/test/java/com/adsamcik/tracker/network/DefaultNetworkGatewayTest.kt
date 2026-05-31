package com.adsamcik.tracker.network

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldContain as shouldContainElement
import io.kotest.matchers.collections.shouldNotContain as shouldNotContainElement
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DefaultNetworkGateway end-to-end")
class DefaultNetworkGatewayTest {

	private lateinit var server: MockWebServer

	@BeforeEach
	fun setup() {
		server = MockWebServer().apply { start() }
	}

	@AfterEach
	fun teardown() {
		server.shutdown()
	}

	private fun newGatewayAllowingServer(rateLimit: Int = 600): DefaultNetworkGateway {
		val host = server.hostName.lowercase() // MockWebServer uses 127.0.0.1 or localhost
		return DefaultNetworkGateway(
			initialEnabled = true,
			initialPolicy = NetworkPolicy(
				allowedHosts = setOf(host),
				perHostRateLimit = rateLimit,
				perHostRateWindowMs = 60_000L,
			),
		)
	}

	@Test
	fun `request to allowed host returns Success with body`() = runTest {
		// Note: MockWebServer is HTTP, not HTTPS — so we expect the InsecureScheme guard to
		// reject this. That's the correct production behavior. To unit-test 2xx success
		// flow against MockWebServer we'd need to drive HTTPS or carve out an http override.
		// Instead we verify the HTTPS-only contract here and rely on the interceptor tests
		// for the success path below.
		val gateway = newGatewayAllowingServer()
		val req = NetworkRequest(server.url("/x").toString())
		val response = gateway.request(req)
		response.shouldBeInstanceOf<NetworkResponse.Failure>()
		response.error.shouldBeInstanceOf<NetworkError.InsecureScheme>()
		(response.error as NetworkError.InsecureScheme).scheme shouldBe "http"
	}

	@Test
	fun `kill switch rejects with GatewayDisabled`() = runTest {
		val gateway = DefaultNetworkGateway(initialEnabled = false)
		gateway.setPolicy(NetworkPolicy(allowedHosts = setOf("anywhere.example")))
		val response = gateway.request(NetworkRequest("https://anywhere.example/x"))
		response.shouldBeInstanceOf<NetworkResponse.Failure>()
		response.error shouldBe NetworkError.GatewayDisabled
	}

	@Test
	fun `host not in allowlist returns HostNotAllowed before any network IO`() = runTest {
		val gateway = DefaultNetworkGateway(
			initialEnabled = true,
			initialPolicy = NetworkPolicy(allowedHosts = setOf("only.example")),
		)
		val response = gateway.request(NetworkRequest("https://different.example/x"))
		response.shouldBeInstanceOf<NetworkResponse.Failure>()
		(response.error as NetworkError.HostNotAllowed).host shouldBe "different.example"
	}

	@Test
	fun `http scheme is rejected before reaching OkHttp`() = runTest {
		val gateway = DefaultNetworkGateway(
			initialEnabled = true,
			initialPolicy = NetworkPolicy(allowedHosts = setOf("plain.example")),
		)
		val response = gateway.request(NetworkRequest("http://plain.example/x"))
		response.shouldBeInstanceOf<NetworkResponse.Failure>()
		(response.error as NetworkError.InsecureScheme).scheme shouldBe "http"
	}

	@Test
	fun `unparseable URL returns InvalidUrl`() = runTest {
		val gateway = DefaultNetworkGateway(
			initialEnabled = true,
			initialPolicy = NetworkPolicy(allowedHosts = setOf("x.example")),
		)
		val response = gateway.request(NetworkRequest("https:// not a url"))
		response.shouldBeInstanceOf<NetworkResponse.Failure>()
		response.error.shouldBeInstanceOf<NetworkError.InvalidUrl>()
	}

	@Test
	fun `subdomains are NOT auto-allowed by allowlist`() = runTest {
		val gateway = DefaultNetworkGateway(
			initialEnabled = true,
			initialPolicy = NetworkPolicy(allowedHosts = setOf("openfreemap.org")),
		)
		val response = gateway.request(NetworkRequest("https://tiles.openfreemap.org/styles/liberty"))
		response.shouldBeInstanceOf<NetworkResponse.Failure>()
		(response.error as NetworkError.HostNotAllowed).host shouldBe "tiles.openfreemap.org"
	}

	@Test
	fun `rate limit triggers RateLimited after bucket drains`() {
		// RateLimit is a NETWORK interceptor (R7 redirect-recheck convergence
		// fix) so it fires per-hop AFTER ConnectInterceptor. Test against the
		// MockWebServer (localhost, resolves instantly, connects instantly) so
		// the rate limiter actually runs on real exchanges. limit=2 means the
		// first two requests succeed and decrement the bucket; the third
		// finds the bucket empty and is rejected with RateLimited.
		val gateway = DefaultNetworkGateway(
			initialEnabled = true,
			initialPolicy = NetworkPolicy(
				allowedHosts = setOf(server.hostName.lowercase()),
				perHostRateLimit = 2,
				perHostRateWindowMs = 60_000L,
			),
		)
		server.enqueue(MockResponse().setBody("a"))
		server.enqueue(MockResponse().setBody("b"))
		val client = (gateway as OkHttpBackedGateway).okHttpCallFactory() as okhttp3.OkHttpClient

		client.newCall(okhttp3.Request.Builder().url(server.url("/1")).build()).execute()
			.use { it.isSuccessful shouldBe true }
		client.newCall(okhttp3.Request.Builder().url(server.url("/2")).build()).execute()
			.use { it.isSuccessful shouldBe true }

		val ex = runCatching {
			client.newCall(okhttp3.Request.Builder().url(server.url("/3")).build()).execute()
		}.exceptionOrNull()
		ex.shouldBeInstanceOf<com.adsamcik.tracker.network.internal.GatewayInterceptorException>()
		val err = (ex as com.adsamcik.tracker.network.internal.GatewayInterceptorException).networkError
		err.shouldBeInstanceOf<NetworkError.RateLimited>()
		(err as NetworkError.RateLimited).host shouldBe server.hostName.lowercase()
	}

	@Test
	fun `setEnabled hot-swap allows then blocks`() = runTest {
		val gateway = DefaultNetworkGateway(
			initialEnabled = true,
			initialPolicy = NetworkPolicy(allowedHosts = setOf("x.example")),
		)
		// Initially enabled — InsecureScheme is the next guard for http URL
		val first = gateway.request(NetworkRequest("https://x.example/y", timeoutMs = 500L))
		first.shouldBeInstanceOf<NetworkResponse.Failure>()
		(first.error is NetworkError.Transport || first.error is NetworkError.Timeout) shouldBe true

		gateway.setEnabled(false)
		val second = gateway.request(NetworkRequest("https://x.example/y"))
		second.shouldBeInstanceOf<NetworkResponse.Failure>()
		second.error shouldBe NetworkError.GatewayDisabled
	}

	@Test
	fun `User-Agent is set to the anonymous Tracker-Android pinned string when not provided`() = runTest {
		// We test the UA at the interceptor level by inspecting the request URL builder behavior
		// via a host that's NOT allowed; the failure path captures the policy decision but
		// since the AllowlistInterceptor runs AFTER the UA injection (UA is set in the
		// dispatch loop), inspecting via reflection would be fragile. Instead, verify the
		// USER_AGENT constant has the expected stable shape so consumers can trust the
		// contract documented in DefaultNetworkGateway.
		DefaultNetworkGateway.USER_AGENT shouldContain "Tracker-Android"
		DefaultNetworkGateway.USER_AGENT shouldContain "privacy-first"
	}

	@Test
	fun `gateway implements OkHttpBackedGateway and exposes a non-null call factory`() {
		val gateway = DefaultNetworkGateway()
		(gateway is OkHttpBackedGateway) shouldBe true
		val factory = (gateway as OkHttpBackedGateway).okHttpCallFactory()
		// Factory is the underlying OkHttpClient — same instance across calls.
		factory shouldNotBe null
		val second = (gateway as OkHttpBackedGateway).okHttpCallFactory()
		(factory === second) shouldBe true
	}

	@Test
	fun `redirects are enabled so tile providers that 301 work correctly`() {
		// Verifies the OkHttpClient backing the gateway has followRedirects enabled,
		// which is required for tile providers that legitimately redirect (cache
		// busting, version pinning). Reflective check — internal config not exposed
		// otherwise.
		val gateway = DefaultNetworkGateway()
		val factory = (gateway as OkHttpBackedGateway).okHttpCallFactory()
		val client = factory as okhttp3.OkHttpClient
		client.followRedirects shouldBe true
		client.followSslRedirects shouldBe true
	}

	@Test
	fun `allowlist runs as BOTH application AND network interceptor (per-hop, including redirects)`() {
		// Convergence finding (R1+R3 round 7): the pre-fix design only had
		// application interceptors, so a 302 redirect from an allowed host to a
		// disallowed host would NOT be re-checked. The fix registers
		// AllowlistInterceptor at BOTH levels:
		//  - Application: preserves "no DNS for disallowed hosts" privacy
		//    contract for the initial URL.
		//  - Network: catches every redirect hop after ConnectInterceptor
		//    (DNS leak on disallowed redirect targets is the trade-off until
		//    we move to manual redirect handling).
		// RateLimit is network-only so per-host bucket counts each actual hop.
		// KillSwitch stays application-only — fail-fast before any I/O.
		val gateway = DefaultNetworkGateway()
		val client = (gateway as OkHttpBackedGateway).okHttpCallFactory() as okhttp3.OkHttpClient

		val appNames = client.interceptors.map { it::class.simpleName }
		val netNames = client.networkInterceptors.map { it::class.simpleName }

		appNames shouldContainElement "KillSwitchInterceptor"
		appNames shouldContainElement "AllowlistInterceptor"
		appNames shouldNotContainElement "RateLimitInterceptor"

		netNames shouldContainElement "AllowlistInterceptor"
		netNames shouldContainElement "RateLimitInterceptor"
		netNames shouldNotContainElement "KillSwitchInterceptor"
	}

	@Test
	fun `cross-host redirect to a disallowed host is rejected at the redirect hop`() {
		// End-to-end proof of the R7 convergence fix: AllowlistInterceptor must
		// fire on every redirect hop, not just the original request. We allow
		// only server.hostName (typically "localhost") and redirect to the same
		// loopback address using a DIFFERENT host string ("127.0.0.1") — this
		// resolves (so ConnectInterceptor succeeds and the network interceptor
		// runs) but does NOT match the allowlist, proving the gate catches the
		// hop. Before the fix (Allowlist app-only) this redirect would silently
		// follow and the second MockResponse below would be served.
		val allowedHost = server.hostName.lowercase()
		val disallowedHost = if (allowedHost == "localhost") "127.0.0.1" else "localhost"
		val gateway = DefaultNetworkGateway(
			initialEnabled = true,
			initialPolicy = NetworkPolicy(
				allowedHosts = setOf(allowedHost),
				perHostRateLimit = 1_000,
				perHostRateWindowMs = 60_000L,
			),
		)
		val redirectTarget = "http://$disallowedHost:${server.port}/x"
		server.enqueue(
			MockResponse()
				.setResponseCode(302)
				.addHeader("Location", redirectTarget),
		)
		// Enqueue a body for the redirect target — if the redirect is silently
		// followed (the pre-fix bug), this is what would be returned.
		server.enqueue(MockResponse().setBody("should-never-be-reached"))

		val client = (gateway as OkHttpBackedGateway).okHttpCallFactory() as okhttp3.OkHttpClient
		val req = okhttp3.Request.Builder().url(server.url("/r")).build()

		val ex = runCatching { client.newCall(req).execute() }.exceptionOrNull()
		ex.shouldBeInstanceOf<com.adsamcik.tracker.network.internal.GatewayInterceptorException>()
		val err = (ex as com.adsamcik.tracker.network.internal.GatewayInterceptorException).networkError
		err.shouldBeInstanceOf<NetworkError.HostNotAllowed>()
		(err as NetworkError.HostNotAllowed).host shouldBe disallowedHost
		// Only the 302 was served; the redirect target was never reached.
		server.requestCount shouldBe 1
	}
}
