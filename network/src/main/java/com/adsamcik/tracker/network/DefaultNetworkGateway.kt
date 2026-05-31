package com.adsamcik.tracker.network

import com.adsamcik.tracker.network.internal.AllowlistInterceptor
import com.adsamcik.tracker.network.internal.GatewayInterceptorException
import com.adsamcik.tracker.network.internal.HttpsOnlyInterceptor
import com.adsamcik.tracker.network.internal.KillSwitchInterceptor
import com.adsamcik.tracker.network.internal.RateLimitInterceptor
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Production [NetworkGateway] backed by OkHttp.
 *
 * Single instance per process (singleton scope in Hilt). All consumers
 * suspend on [request]; OkHttp's [Call.enqueue] dispatches I/O on its own
 * thread pool, so we resume the coroutine on a background dispatcher.
 *
 * # Interceptor chain (in order)
 *
 *  Application-level (run ONCE per logical call, before redirect follow):
 *
 *  1. [KillSwitchInterceptor] — rejects with [NetworkError.GatewayDisabled]
 *     when the kill switch is off. First so it fast-fails before any work.
 *  2. [AllowlistInterceptor] — rejects with [NetworkError.HostNotAllowed]
 *     for any URL whose host is not in [NetworkPolicy.allowedHosts]. Application
 *     placement keeps the privacy contract "no DNS / TCP for disallowed hosts"
 *     for the initial URL. Placed before [HttpsOnlyInterceptor] so a disallowed
 *     host is reported as "not allowed" rather than leaking the additional
 *     fact that the host would otherwise have been HTTPS-rejected.
 *  3. [HttpsOnlyInterceptor] — rejects with [NetworkError.InsecureScheme]
 *     for any URL whose scheme is not `https`. The suspend wrapper has its
 *     own pre-OkHttp HTTPS guard but consumers using [okHttpCallFactory]
 *     directly (e.g. MapLibre) bypass that path; this interceptor enforces
 *     the contract for them too.
 *
 *  Network-level (run for EACH network exchange, including every redirect
 *  hop — required so cross-host / cross-scheme redirects cannot bypass):
 *
 *  4. [AllowlistInterceptor] — same gate, second instance. Application-level
 *     only sees the originating request; redirects are issued by
 *     OkHttp's internal `RetryAndFollowUpInterceptor` and must be re-checked.
 *     OkHttp's chain runs network interceptors after `ConnectInterceptor`, so
 *     a redirect target's DNS + TCP cost is incurred before the gate fires;
 *     the request BODY is still blocked. To eliminate the DNS cost for
 *     disallowed redirect targets we'd need [okhttp3.OkHttpClient.followRedirects]
 *     = false and a manual redirect follower — that's a future hardening pass.
 *  5. [HttpsOnlyInterceptor] — same gate, second instance. Catches the case
 *     where an allowed HTTPS host responds with a 302 to a cleartext `http://`
 *     URL. OkHttp's `followSslRedirects(true)` would otherwise downgrade.
 *  6. [RateLimitInterceptor] — per-host token bucket. Network-level so each
 *     hop counts against the target host's bucket (redirect-accurate). Rejects
 *     with [NetworkError.RateLimited] when the bucket is empty.
 *
 * Each interceptor that rejects throws a [GatewayInterceptorException]
 * carrying the correct [NetworkError]; the outer suspend wrapper translates
 * back into a [NetworkResponse.Failure].
 *
 * # Privacy
 *
 *  - HTTP (cleartext) requests are rejected at the URL parse step with
 *    [NetworkError.InsecureScheme] BEFORE we hand them to OkHttp.
 *  - No identifying headers are injected. The default OkHttp User-Agent is
 *    suppressed in favour of a stable, anonymous string so request fingerprint
 *    doesn't include the device's exact OkHttp/Android version.
 *  - Cookie jar is disabled (no cookies sent or stored).
 */
class DefaultNetworkGateway(
	initialEnabled: Boolean = false,
	initialPolicy: NetworkPolicy = NetworkPolicy.EMPTY,
	/**
	 * Coroutine dispatcher provider for [request]'s I/O work. Defaults to
	 * [DefaultDispatchersProvider] which routes I/O to `Dispatchers.IO`. Tests
	 * pass a [com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider]
	 * to force suspend/resume on a controlled scheduler. Also keeps `:network`
	 * out of direct dependencies on the `Dispatchers` whole-object import,
	 * which the architectural fitness test now bans outside `:sbase`.
	 */
	private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
) : NetworkGateway, OkHttpBackedGateway {

	private val _isEnabled = MutableStateFlow(initialEnabled)
	override val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

	private val _policy = MutableStateFlow(initialPolicy)
	override val policy: StateFlow<NetworkPolicy> = _policy.asStateFlow()

	private val client: OkHttpClient = OkHttpClient.Builder()
		.connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
		.readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
		.writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
		.retryOnConnectionFailure(true)
		// Redirects are followed by default. Required so the allowlist remains
		// useful for tile/style providers that legitimately redirect within
		// their own host (e.g. cache-busting query strings, version pinning).
		// AllowlistInterceptor is registered as BOTH an application AND a
		// network interceptor: app-level preserves the "no DNS for disallowed
		// hosts" privacy contract for the initial URL; network-level catches
		// every redirect hop (which OkHttp's RetryAndFollowUpInterceptor
		// issues as fresh requests after the app-level chain). RateLimit is
		// network-only so its per-host bucket charges the actual hop host,
		// matching how OkHttp dispatches redirects to potentially different
		// hosts. KillSwitch is application-only — it fails fast before any
		// connection work.
		.followRedirects(true)
		.followSslRedirects(true)
		.addInterceptor(KillSwitchInterceptor(enabledSource = { _isEnabled.value }))
		.addInterceptor(AllowlistInterceptor(policySource = { _policy.value }))
		.addInterceptor(HttpsOnlyInterceptor())
		.addNetworkInterceptor(AllowlistInterceptor(policySource = { _policy.value }))
		.addNetworkInterceptor(HttpsOnlyInterceptor())
		.addNetworkInterceptor(RateLimitInterceptor(policySource = { _policy.value }))
		.build()

	override fun okHttpCallFactory(): okhttp3.Call.Factory = client

	override suspend fun request(req: NetworkRequest): NetworkResponse = withContext(dispatchers.io) {
		// Parse + scheme guard happen synchronously before we touch OkHttp.
		val parsedUri = try {
			URI(req.url)
		} catch (_: URISyntaxException) {
			return@withContext NetworkResponse.Failure(req, NetworkError.InvalidUrl(req.url, "URISyntaxException"))
		} catch (_: IllegalArgumentException) {
			return@withContext NetworkResponse.Failure(req, NetworkError.InvalidUrl(req.url, "IllegalArgumentException"))
		}
		val scheme = parsedUri.scheme ?: ""
		if (!scheme.equals("https", ignoreCase = true)) {
			return@withContext NetworkResponse.Failure(req, NetworkError.InsecureScheme(scheme))
		}
		if (parsedUri.host.isNullOrBlank()) {
			return@withContext NetworkResponse.Failure(req, NetworkError.InvalidUrl(req.url, "missing host"))
		}

		val builder = Request.Builder().url(req.url)
		val contentType = req.headers["Content-Type"]?.toMediaTypeOrNull()
		when (req.method) {
			HttpMethod.GET -> builder.get()
			HttpMethod.HEAD -> builder.head()
			HttpMethod.DELETE -> builder.delete(if (req.body.isEmpty()) null else req.body.toRequestBody(contentType))
			HttpMethod.POST -> builder.post(req.body.toRequestBody(contentType))
			HttpMethod.PUT -> builder.put(req.body.toRequestBody(contentType))
			HttpMethod.PATCH -> builder.patch(req.body.toRequestBody(contentType))
		}
		req.headers.forEach { (name, value) -> builder.header(name, value) }
		// Inject a stable, anonymous UA to prevent device-version fingerprinting.
		if (!req.headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
			builder.header("User-Agent", USER_AGENT)
		}

		val perRequestClient = if (req.timeoutMs == NetworkRequest.DEFAULT_TIMEOUT_MS) {
			client
		} else {
			client.newBuilder()
				.callTimeout(req.timeoutMs, TimeUnit.MILLISECONDS)
				.build()
		}

		val startNs = System.nanoTime()
		try {
			val outcome = suspendCancellableCoroutine<HttpOutcome> { cont ->
				val call = perRequestClient.newCall(builder.build())
				cont.invokeOnCancellation { runCatching { call.cancel() } }
				call.enqueue(object : Callback {
					override fun onResponse(call: Call, response: Response) {
						cont.resume(HttpOutcome.Ok(response))
					}
					override fun onFailure(call: Call, e: IOException) {
						if (e is GatewayInterceptorException) {
							cont.resume(HttpOutcome.Rejected(e.networkError))
						} else {
							cont.resumeWith(Result.failure(e))
						}
					}
				})
			}
			when (outcome) {
				is HttpOutcome.Rejected -> NetworkResponse.Failure(req, outcome.error)
				is HttpOutcome.Ok -> outcome.response.use { response ->
					val latencyMs = (System.nanoTime() - startNs) / 1_000_000L
					val bodyBytes = response.body?.bytes() ?: ByteArray(0)
					val headers = response.headers.toMultimap()
						.mapValues { it.value.joinToString(", ") }
					if (response.code in 200..299) {
						NetworkResponse.Success(
							request = req,
							statusCode = response.code,
							body = bodyBytes,
							headers = headers,
							latencyMs = latencyMs,
						)
					} else {
						NetworkResponse.Failure(
							request = req,
							error = NetworkError.HttpStatus(response.code, bodyBytes, headers),
						)
					}
				}
			}
		} catch (e: java.net.SocketTimeoutException) {
			NetworkResponse.Failure(req, NetworkError.Timeout)
		} catch (e: MalformedURLException) {
			NetworkResponse.Failure(req, NetworkError.InvalidUrl(req.url, e.message ?: "malformed"))
		} catch (e: IOException) {
			NetworkResponse.Failure(req, NetworkError.Transport(e.message ?: "io error", e))
		}
	}

	/** Internal outcome carrier that lets the suspend wrapper distinguish gateway rejections from OkHttp responses without subclassing the (final, internal-constructor) [Response] type. */
	private sealed interface HttpOutcome {
		data class Ok(val response: Response) : HttpOutcome
		data class Rejected(val error: NetworkError) : HttpOutcome
	}

	override fun setEnabled(enabled: Boolean) {
		val wasEnabled = _isEnabled.value
		_isEnabled.value = enabled
		// R2 round 7: when flipping the kill switch OFF, cancel every in-flight
		// queued + executing call on the shared OkHttp Dispatcher. Without this,
		// online tile fetches launched a moment before the user toggles offline
		// continue to completion (potentially several seconds of cleartext
		// metadata in flight) — the KillSwitchInterceptor only stops NEW calls.
		// MapLibre uses the same call factory, so its tile/style requests are
		// cancelled too. Per-request OkHttpClients built via newBuilder() share
		// this Dispatcher instance, so cancelAll() reaches them as well.
		if (wasEnabled && !enabled) {
			runCatching { client.dispatcher.cancelAll() }
		}
	}

	override fun setPolicy(policy: NetworkPolicy) {
		_policy.value = policy
	}

	companion object {
		private const val CONNECT_TIMEOUT_MS: Long = 10_000L
		private const val READ_TIMEOUT_MS: Long = 30_000L
		private const val WRITE_TIMEOUT_MS: Long = 30_000L

		/**
		 * Stable, anonymous User-Agent. Identifies the app + major version only
		 * — no exact OkHttp version, no Android version, no device. Pinning
		 * a stable string keeps the request fingerprint indistinguishable
		 * across installs.
		 */
		const val USER_AGENT: String = "Tracker-Android/10 (privacy-first, https://github.com/adsamcik/Tracker-Android)"
	}
}
