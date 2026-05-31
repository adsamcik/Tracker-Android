package com.adsamcik.tracker.network

import okhttp3.Call

/**
 * Capability interface exposed by [NetworkGateway] implementations that wrap
 * an OkHttp client internally. Lets libraries which integrate at the OkHttp
 * level (e.g. MapLibre Android's
 * `org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient`) route
 * their traffic through the gateway's interceptor chain (kill switch →
 * allowlist → rate limit) instead of using a separate, ungated HTTP client.
 *
 * Test fakes ([FakeNetworkGateway]) deliberately do NOT implement this — they
 * have no real OkHttp client and only model the [NetworkGateway] contract.
 * Consumers MUST therefore use `(gateway as? OkHttpBackedGateway)?.…` so the
 * fake's absence of the capability is handled gracefully in tests.
 */
interface OkHttpBackedGateway {
	/**
	 * The underlying [Call.Factory] (typically an `OkHttpClient`) backing this
	 * gateway. All requests issued through the returned factory pass through
	 * the same interceptor chain as [NetworkGateway.request], so the kill
	 * switch, allowlist, rate limit, and HTTPS-only guard apply uniformly.
	 *
	 * The returned factory is process-stable — callers can register it once
	 * (e.g. `HttpRequestUtil.setOkHttpClient(factory)`) at app startup and
	 * rely on the gateway's `setEnabled` / `setPolicy` to take effect on
	 * every subsequent request without re-registration.
	 */
	fun okHttpCallFactory(): Call.Factory
}
