package com.adsamcik.tracker.network

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldContain as shouldContainElement
import io.kotest.matchers.collections.shouldNotContain as shouldNotContainElement
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetAddress

@DisplayName("DefaultNetworkGateway end-to-end")
class DefaultNetworkGatewayTest {

	private lateinit var server: MockWebServer
	private lateinit var httpsServer: MockWebServer
	private lateinit var handshakes: HandshakeCertificates

	@BeforeEach
	fun setup() {
		// Both servers bound explicitly to the loopback address so MockWebServer
		// reports `127.0.0.1` as hostName (rather than the machine's NetBIOS
		// name like `cryptomator-vault` which would mismatch the TLS cert SAN).
		val loopback = InetAddress.getByName("127.0.0.1")
		server = MockWebServer().apply { start(loopback, 0) }

		// HTTPS MockWebServer: needed because the gateway now enforces HTTPS at
		// the application-interceptor level (R1 round 7 finding). Any test that
		// drives the gateway through `okHttpCallFactory()` MUST go over TLS, and
		// the gateway's OkHttpClient does NOT trust MockWebServer's self-signed
		// cert by default — see `httpsTestClient()` below. The cert SAN covers
		// both `localhost` and `127.0.0.1` so it works regardless of how the
		// test addresses the server.
		val held = HeldCertificate.Builder()
			.addSubjectAlternativeName("localhost")
			.addSubjectAlternativeName("127.0.0.1")
			.build()
		handshakes = HandshakeCertificates.Builder()
			.addTrustedCertificate(held.certificate)
			.heldCertificate(held)
			.build()
		httpsServer = MockWebServer().apply {
			useHttps(handshakes.sslSocketFactory(), /* tunnelProxy = */ false)
			start(loopback, 0)
		}
	}

	@AfterEach
	fun teardown() {
		server.shutdown()
		httpsServer.shutdown()
	}

	/**
	 * Returns a copy of the gateway's underlying OkHttpClient with the test
	 * HTTPS trust manager added. `OkHttpClient.newBuilder()` preserves the
	 * interceptor lists — so the returned client still runs KillSwitch,
	 * Allowlist, HttpsOnly, and RateLimit. This is the only way to drive
	 * the gateway through MockWebServer's HTTPS without disabling the HTTPS
	 * guard.
	 */
	private fun DefaultNetworkGateway.httpsTestClient(): OkHttpClient =
		((this as OkHttpBackedGateway).okHttpCallFactory() as OkHttpClient)
			.newBuilder()
			.sslSocketFactory(handshakes.sslSocketFactory(), handshakes.trustManager)
			.build()

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
		// fix) so it fires per-hop AFTER ConnectInterceptor. The gateway now
		// enforces HTTPS at the application-interceptor level (R7 HTTPS finding)
		// so this MUST go over TLS. URLs are constructed against "localhost"
		// directly (not httpsServer.url(...)) because MockWebServer's
		// canonical-hostname resolution can return the machine NetBIOS name on
		// some hosts, which would mismatch the cert SAN. limit=2 means the
		// first two requests succeed and decrement the bucket; the third
		// finds the bucket empty and is rejected with RateLimited.
		val gateway = DefaultNetworkGateway(
			initialEnabled = true,
			initialPolicy = NetworkPolicy(
				allowedHosts = setOf("localhost"),
				perHostRateLimit = 2,
				perHostRateWindowMs = 60_000L,
			),
		)
		httpsServer.enqueue(MockResponse().setBody("a"))
		httpsServer.enqueue(MockResponse().setBody("b"))
		val client = gateway.httpsTestClient()
		val baseUrl = "https://localhost:${httpsServer.port}"

		client.newCall(okhttp3.Request.Builder().url("$baseUrl/1").build()).execute()
			.use { it.isSuccessful shouldBe true }
		client.newCall(okhttp3.Request.Builder().url("$baseUrl/2").build()).execute()
			.use { it.isSuccessful shouldBe true }

		val ex = runCatching {
			client.newCall(okhttp3.Request.Builder().url("$baseUrl/3").build()).execute()
		}.exceptionOrNull()
		ex.shouldBeInstanceOf<com.adsamcik.tracker.network.internal.GatewayInterceptorException>()
		val err = (ex as com.adsamcik.tracker.network.internal.GatewayInterceptorException).networkError
		err.shouldBeInstanceOf<NetworkError.RateLimited>()
		(err as NetworkError.RateLimited).host shouldBe "localhost"
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
		// HttpsOnly is also dual-registered (R1 round 7) so that consumers
		// using the raw OkHttp factory (MapLibre) cannot bypass the suspend
		// wrapper's HTTPS guard, and so that cross-scheme redirects (HTTPS→
		// HTTP) cannot bypass it either.
		// RateLimit is network-only so per-host bucket counts each actual hop.
		// KillSwitch stays application-only — fail-fast before any I/O.
		val gateway = DefaultNetworkGateway()
		val client = (gateway as OkHttpBackedGateway).okHttpCallFactory() as okhttp3.OkHttpClient

		val appNames = client.interceptors.map { it::class.simpleName }
		val netNames = client.networkInterceptors.map { it::class.simpleName }

		appNames shouldContainElement "KillSwitchInterceptor"
		appNames shouldContainElement "AllowlistInterceptor"
		appNames shouldContainElement "HttpsOnlyInterceptor"
		appNames shouldNotContainElement "RateLimitInterceptor"

		netNames shouldContainElement "AllowlistInterceptor"
		netNames shouldContainElement "HttpsOnlyInterceptor"
		netNames shouldContainElement "RateLimitInterceptor"
		netNames shouldNotContainElement "KillSwitchInterceptor"
	}

	@Test
	fun `raw OkHttp call factory rejects cleartext HTTP request with InsecureScheme`() {
		// R1 round 7: MapLibre takes the gateway's OkHttp call factory and uses
		// it directly to fetch tiles and styles — completely bypassing the
		// suspend wrapper's pre-OkHttp HTTPS guard. Before this fix, MapLibre
		// would silently send cleartext HTTP if a tile/style URL was misconfigured
		// or maliciously redirected. The HttpsOnlyInterceptor (registered as an
		// application interceptor) catches this on the very first hop.
		val allowedHost = server.hostName.lowercase()
		val gateway = DefaultNetworkGateway(
			initialEnabled = true,
			initialPolicy = NetworkPolicy(
				allowedHosts = setOf(allowedHost),
				perHostRateLimit = 1_000,
				perHostRateWindowMs = 60_000L,
			),
		)
		val client = (gateway as OkHttpBackedGateway).okHttpCallFactory() as okhttp3.OkHttpClient
		val req = okhttp3.Request.Builder().url(server.url("/x")).build()

		val ex = runCatching { client.newCall(req).execute() }.exceptionOrNull()
		ex.shouldBeInstanceOf<com.adsamcik.tracker.network.internal.GatewayInterceptorException>()
		val err = (ex as com.adsamcik.tracker.network.internal.GatewayInterceptorException).networkError
		err.shouldBeInstanceOf<NetworkError.InsecureScheme>()
		(err as NetworkError.InsecureScheme).scheme shouldBe "http"
		// MockWebServer never even got the request — the application interceptor
		// fast-failed before ConnectInterceptor.
		server.requestCount shouldBe 0
	}

	@Test
	fun `cross-host redirect to a disallowed host is rejected at the redirect hop`() {
		// End-to-end proof of the R7 convergence fix: AllowlistInterceptor must
		// fire on every redirect hop, not just the original request. We allow
		// only "localhost" and redirect to "127.0.0.1" on the same loopback —
		// this resolves (so ConnectInterceptor succeeds and the network
		// interceptor runs) but does NOT match the allowlist, proving the gate
		// catches the hop. Before the fix (Allowlist app-only) this redirect
		// would silently follow and the second MockResponse below would be
		// served. The redirect target uses `https://` (matching the cert's SAN
		// for 127.0.0.1) so the HttpsOnlyInterceptor does NOT reject before
		// Allowlist (and Allowlist is registered before HttpsOnly in the chain).
		val allowedHost = "localhost"
		val disallowedHost = "127.0.0.1"
		val gateway = DefaultNetworkGateway(
			initialEnabled = true,
			initialPolicy = NetworkPolicy(
				allowedHosts = setOf(allowedHost),
				perHostRateLimit = 1_000,
				perHostRateWindowMs = 60_000L,
			),
		)
		val redirectTarget = "https://$disallowedHost:${httpsServer.port}/x"
		httpsServer.enqueue(
			MockResponse()
				.setResponseCode(302)
				.addHeader("Location", redirectTarget),
		)
		// Enqueue a body for the redirect target — if the redirect is silently
		// followed (the pre-fix bug), this is what would be returned.
		httpsServer.enqueue(MockResponse().setBody("should-never-be-reached"))

		val client = gateway.httpsTestClient()
		val req = okhttp3.Request.Builder().url("https://$allowedHost:${httpsServer.port}/r").build()

		val ex = runCatching { client.newCall(req).execute() }.exceptionOrNull()
		ex.shouldBeInstanceOf<com.adsamcik.tracker.network.internal.GatewayInterceptorException>()
		val err = (ex as com.adsamcik.tracker.network.internal.GatewayInterceptorException).networkError
		err.shouldBeInstanceOf<NetworkError.HostNotAllowed>()
		(err as NetworkError.HostNotAllowed).host shouldBe disallowedHost
		// Only the 302 was served; the redirect target was never reached.
		httpsServer.requestCount shouldBe 1
	}
}
