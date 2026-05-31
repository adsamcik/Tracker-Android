package com.adsamcik.tracker.network

/**
 * Policy governing what [NetworkGateway.request] permits.
 *
 * Defaults to **deny all** ([EMPTY]) — the gateway accepts nothing until a
 * feature explicitly publishes a permissive policy via
 * [NetworkGateway.setPolicy]. This is by design: opt-in is the only safe
 * default for a privacy-first app.
 *
 * @property allowedHosts Exact host strings that may be requested. The
 *   gateway normalizes both this set and the requested URL's host to lower
 *   case. Subdomains are NOT auto-allowed — list every host you need
 *   (`tiles.openfreemap.org` does not imply `openfreemap.org`).
 * @property perHostRateLimit Maximum requests per host per
 *   [perHostRateWindowMs]. Token-bucket semantics: bucket refills linearly
 *   at `perHostRateLimit / perHostRateWindowMs` and bursts up to the limit.
 *   Zero or negative disables rate limiting (not recommended for prod).
 * @property perHostRateWindowMs The rate-limit window in milliseconds.
 *   Default 60_000 (one minute).
 */
data class NetworkPolicy(
	val allowedHosts: Set<String>,
	val perHostRateLimit: Int = DEFAULT_RATE_LIMIT,
	val perHostRateWindowMs: Long = DEFAULT_RATE_WINDOW_MS,
) {
	val normalizedAllowedHosts: Set<String> by lazy {
		allowedHosts.mapTo(HashSet(allowedHosts.size)) { it.lowercase().trim() }
	}

	fun allows(host: String): Boolean =
		host.lowercase().trim() in normalizedAllowedHosts

	companion object {
		const val DEFAULT_RATE_LIMIT: Int = 600
		const val DEFAULT_RATE_WINDOW_MS: Long = 60_000L

		/** Deny-all default policy. Returned by [NetworkGateway.policy] until a feature publishes a real one. */
		val EMPTY = NetworkPolicy(allowedHosts = emptySet())
	}
}
