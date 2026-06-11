package com.adsamcik.tracker.network

import kotlinx.coroutines.flow.StateFlow

/**
 * A feature-scoped contributor that publishes its current view of "what hosts
 * I need network access to, with what rate limits". The
 * [NetworkPolicyAggregator] combines every contributor's [contribution] into
 * the single effective [NetworkPolicy] driven onto the [NetworkGateway].
 *
 * # Why contributors instead of direct gateway calls
 *
 * Historically `MapStore` was the SOLE writer of [NetworkGateway] state via
 * [NetworkGateway.setEnabled] + [NetworkGateway.setPolicy]. Any second
 * consumer (e.g. an OSM PMTiles offline pre-cache download) wanting its own
 * allowlist + rate limit would fight `MapStore` for the global state — last
 * writer wins, the loser's hosts get silently locked out.
 *
 * Contributors solve this:
 *
 *  - Every feature that needs network access implements
 *    [NetworkPolicyContributor] and Hilt-registers itself via
 *    `@IntoSet`.
 *  - When the feature is "off" (user toggle disabled, download finished),
 *    the contributor emits [NetworkPolicyContribution.Inactive] —
 *    contributing nothing to the union.
 *  - When the feature is "on", it emits a
 *    [NetworkPolicyContribution.Active] carrying its hosts + per-host limits.
 *  - The aggregator unions every active contribution into a single
 *    [NetworkPolicy] and arms the kill switch iff at least one contributor
 *    is active.
 *
 * Direct [NetworkGateway.setEnabled] / [NetworkGateway.setPolicy] calls from
 * feature code are now an anti-pattern — they will be silently overwritten on
 * the next contributor emission.
 *
 * # Threading
 *
 * [contribution] is a [StateFlow]; the aggregator subscribes once at app
 * startup and stays subscribed for process lifetime. Implementations are
 * responsible for hoisting their preference / state flows onto a stable
 * [StateFlow] (typically via `stateIn(scope, SharingStarted.Eagerly, …)`)
 * so the aggregator always has a value to combine without suspending.
 */
interface NetworkPolicyContributor {
	/**
	 * Stable identifier (e.g. `"map-tiles"`, `"osm-download"`). Used for
	 * debugging, audit logs, and future per-contributor diagnostics. NOT
	 * surfaced in UI — pick something machine-readable and stable across
	 * releases.
	 */
	val id: String

	/**
	 * Reactive contribution. Each emission is this contributor's current
	 * snapshot of "what hosts I need + with what rate limits". When this
	 * contributor is off (e.g. user toggle disabled, download finished),
	 * emit [NetworkPolicyContribution.Inactive].
	 *
	 * The aggregator re-derives the effective policy on every emission, so
	 * implementations should debounce or coalesce upstream noise themselves
	 * if relevant (preferences typically dedupe via `distinctUntilChanged`
	 * already).
	 */
	val contribution: StateFlow<NetworkPolicyContribution>
}

/**
 * One contributor's snapshot of "what should the gateway allow on my behalf
 * right now". Combined across every registered [NetworkPolicyContributor] by
 * [NetworkPolicyAggregator] into the effective [NetworkPolicy].
 */
sealed interface NetworkPolicyContribution {
	/**
	 * This contributor is currently OFF — it contributes no hosts and does
	 * not vote to arm the gateway. If every contributor is [Inactive] the
	 * aggregator disables the kill switch and publishes [NetworkPolicy.EMPTY].
	 */
	data object Inactive : NetworkPolicyContribution

	/**
	 * This contributor is currently ON — the gateway must allow these hosts
	 * and the kill switch must be armed.
	 *
	 * @property allowedHosts Exact hosts (lower-case, trimmed by
	 *   [NetworkPolicy]) this contributor requires. Subdomain wildcards are
	 *   NOT supported; list every concrete host.
	 * @property perHostRateLimit Maximum requests per host per
	 *   [perHostRateWindowMs]. When multiple contributors are active the
	 *   aggregator currently takes the MAX (per the design note in the
	 *   coupling refactor) so a contributor with bursty needs (e.g. an
	 *   OSM PMTiles download) isn't bottlenecked by a quieter contributor
	 *   sharing the same effective policy. Per-host fine-grained limits
	 *   are a deferred extension to [NetworkPolicy].
	 * @property perHostRateWindowMs The rate-limit window in milliseconds.
	 *   Defaults to [NetworkPolicy.DEFAULT_RATE_WINDOW_MS] (one minute).
	 */
	data class Active(
		val allowedHosts: Set<String>,
		val perHostRateLimit: Int = NetworkPolicy.DEFAULT_RATE_LIMIT,
		val perHostRateWindowMs: Long = NetworkPolicy.DEFAULT_RATE_WINDOW_MS,
	) : NetworkPolicyContribution
}
