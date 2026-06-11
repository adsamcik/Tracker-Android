package com.adsamcik.tracker.network.internal

import com.adsamcik.tracker.network.NetworkError
import com.adsamcik.tracker.network.NetworkPolicy
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Marker exception thrown by interceptors so the outer dispatch loop in
 * `DefaultNetworkGateway` can map directly into a [NetworkError] without
 * losing the discriminator.
 *
 * OkHttp's Interceptor.intercept contract requires us to either return a
 * [Response] or throw an [IOException]; we use this subclass to carry the
 * sealed [NetworkError] across the throw.
 */
internal class GatewayInterceptorException(
	val networkError: NetworkError,
) : IOException("Gateway interceptor rejected request: $networkError")

/**
 * Interceptor that rejects any request whose URL host is not in the
 * current [NetworkPolicy.allowedHosts].
 *
 * Runs as the FIRST interceptor in the chain so the rejection happens
 * before any DNS / TLS / socket cost.
 */
internal class AllowlistInterceptor(
	private val policySource: () -> NetworkPolicy,
) : Interceptor {
	override fun intercept(chain: Interceptor.Chain): Response {
		val host = chain.request().url.host
		val policy = policySource()
		if (!policy.allows(host)) {
			throw GatewayInterceptorException(NetworkError.HostNotAllowed(host))
		}
		return chain.proceed(chain.request())
	}
}

/**
 * Interceptor that rejects requests when the gateway kill-switch is off.
 *
 * Reads via a function so the value is always current — flipping the
 * StateFlow in the gateway instantly affects subsequent intercepts.
 */
internal class KillSwitchInterceptor(
	private val enabledSource: () -> Boolean,
) : Interceptor {
	override fun intercept(chain: Interceptor.Chain): Response {
		if (!enabledSource()) {
			throw GatewayInterceptorException(NetworkError.GatewayDisabled)
		}
		return chain.proceed(chain.request())
	}
}

/**
 * Interceptor that rejects any request whose URL scheme is not `https`.
 *
 * The suspend wrapper in `DefaultNetworkGateway.request()` already has a
 * pre-OkHttp scheme guard, but consumers using the raw OkHttp call factory
 * (e.g. MapLibre's `setHttpCallFactory` for tile/style requests) bypass
 * that guard. This interceptor enforces the HTTPS-only contract for ALL
 * call paths.
 *
 * Registered at BOTH application AND network levels — application catches
 * the initial cleartext URL before any DNS/TCP cost; network catches every
 * redirect hop (so an HTTPS → HTTP cross-scheme redirect cannot bypass).
 * The interceptor is stateless so dual-registration is idempotent.
 */
internal class HttpsOnlyInterceptor : Interceptor {
	override fun intercept(chain: Interceptor.Chain): Response {
		val scheme = chain.request().url.scheme
		if (!scheme.equals("https", ignoreCase = true)) {
			throw GatewayInterceptorException(NetworkError.InsecureScheme(scheme))
		}
		return chain.proceed(chain.request())
	}
}
