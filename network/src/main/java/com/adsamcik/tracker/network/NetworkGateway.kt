package com.adsamcik.tracker.network

import kotlinx.coroutines.flow.StateFlow

/**
 * Single chokepoint for ALL network egress from the Tracker app.
 *
 * # Why a gateway
 *
 * Tracker-Android is privacy-first and offline-by-default. Historically the
 * app had **zero** network code and actively suppressed the `INTERNET`
 * permission via `tools:node="remove"` so transitive deps could not silently
 * dial home. As features that genuinely need network land (online map tiles,
 * future geocoding, etc.) we need a single auditable place where every
 * outbound request goes — not ad-hoc `HttpURLConnection` or `OkHttp` calls
 * scattered across modules.
 *
 * Every consumer (MapLibre's HTTP client, future API clients, anything else)
 * routes through [request]. The default implementation enforces:
 *
 *  - **Host allowlist** (rejects any URL whose host is not in the active
 *    [NetworkPolicy.allowedHosts]).
 *  - **Per-host rate limit** (token bucket; protects free-tier providers and
 *    flags runaway code paths).
 *  - **Kill switch** ([isEnabled]; flipping to `false` makes every subsequent
 *    [request] fail immediately with [NetworkError.GatewayDisabled]).
 *  - **Audit log** (every request gets a structured log line via `:logger`
 *    with host, path, byte counts, latency — body content is NOT logged).
 *
 * # Privacy contract
 *
 * - The gateway is **opt-in**. With no consumer enabled the kill-switch stays
 *   off and no traffic flows even if INTERNET is granted at the manifest level.
 *
 * - The gateway does **not** add identifying headers (no User-Agent overrides
 *   with device IDs, no auth tokens). Consumers may add their own headers.
 *
 * - The gateway does **not** persist response bodies. Caching is left to
 *   consumers (MapLibre has its own tile cache; future API clients can opt in).
 *
 * # Threading
 *
 * [request] is a suspend function and may be called from any dispatcher.
 * Implementations route I/O onto an appropriate background dispatcher.
 */
interface NetworkGateway {
	/**
	 * Reactive kill-switch state. Consumers that observe this can short-circuit
	 * before even calling [request]; the gateway also checks this on every
	 * request and rejects with [NetworkError.GatewayDisabled] when disabled.
	 *
	 * Default is `false` (gateway disabled) — a feature must explicitly enable
	 * the gateway before traffic flows.
	 */
	val isEnabled: StateFlow<Boolean>

	/**
	 * Current active policy. Implementations may update this in response to
	 * preference changes (e.g. user enables a new provider, custom URL host
	 * gets added to the allowlist).
	 */
	val policy: StateFlow<NetworkPolicy>

	/**
	 * Execute a single network request.
	 *
	 * @return [NetworkResponse.Success] on a 2xx response (with body bytes),
	 *   [NetworkResponse.Failure] on non-2xx HTTP status or transport error
	 *   (with a [NetworkError] discriminator).
	 */
	suspend fun request(req: NetworkRequest): NetworkResponse

	/**
	 * Toggle the kill switch.
	 *
	 * Passing `false` does two things:
	 *  1. **Future requests** entering [request] or the raw [okhttp3.Call.Factory]
	 *     (used by MapLibre online tiles) are rejected immediately with
	 *     [NetworkError.GatewayDisabled] before any DNS / TCP / TLS work.
	 *  2. **In-flight calls** that have already passed interceptors and are
	 *     blocked on socket I/O are actively cancelled via
	 *     `OkHttpClient.dispatcher.cancelAll()`. This prevents an online tile
	 *     fetch launched a moment before the user toggles offline from
	 *     continuing to spend cleartext metadata for the full read timeout
	 *     (interceptors only check on chain entry; they cannot interrupt a
	 *     blocking read).
	 *
	 * Passing `true` simply re-arms the gateway. No in-flight calls exist at
	 * that moment (they were cancelled when the switch was flipped off, or
	 * never started), so there is nothing to resume.
	 *
	 * Intended to be wired to a user-facing setting and to system events
	 * (airplane mode, metered network, low battery -- at the discretion of the
	 * consumer feature).
	 */
	fun setEnabled(enabled: Boolean)

	/**
	 * Replace the active [NetworkPolicy]. Subsequent requests use the new
	 * allowlist / rate limit. In-flight requests use the policy that was
	 * active when they were issued — they do NOT retroactively fail on a
	 * policy that no longer allows their host.
	 */
	fun setPolicy(policy: NetworkPolicy)
}
