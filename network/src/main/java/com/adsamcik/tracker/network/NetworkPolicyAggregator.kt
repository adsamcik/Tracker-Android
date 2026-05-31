package com.adsamcik.tracker.network

import com.adsamcik.tracker.shared.base.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of [NetworkGateway.setEnabled] + [NetworkGateway.setPolicy].
 *
 * Subscribes to every registered [NetworkPolicyContributor] and pushes the
 * union of their active contributions onto the gateway. Construction launches
 * a coroutine on the supplied [scope] that lives for the entire process — the
 * aggregator is intended to be a `@Singleton`, eagerly instantiated from
 * `Application.onCreate` so the gateway is always in a consistent state by
 * the time any feature surfaces UI.
 *
 * # Composition semantics
 *
 *  - **Kill switch.** The gateway is enabled iff at least one contributor is
 *    [NetworkPolicyContribution.Active]. With zero active contributors the
 *    kill switch flips OFF and the gateway publishes [NetworkPolicy.EMPTY],
 *    which fail-closes every subsequent request. This matches the privacy
 *    contract: "opt-in is the only safe default".
 *
 *  - **Allowed hosts.** Union of every active contributor's
 *    [NetworkPolicyContribution.Active.allowedHosts]. Overlapping hosts are
 *    deduplicated (set semantics).
 *
 *  - **Rate limits.** A single per-host rate limit applies to every host in
 *    the effective policy. When multiple contributors are active we take the
 *    MAX of their requested limits — the alternative (MIN) would let a quiet
 *    contributor throttle a bursty one even on hosts the bursty contributor
 *    is the sole consumer of. The same logic applies to
 *    [NetworkPolicyContribution.Active.perHostRateWindowMs] (MAX window =
 *    most permissive). Per-host-within-policy granularity is a future
 *    extension that requires [NetworkPolicy] to grow a `Map<String, Int>`.
 *
 * # Zero-contributors degenerate case
 *
 * Hilt's `@Multibinds` may resolve to an empty set if no module contributes.
 * In that case the constructor short-circuits: no collector is launched and
 * the gateway is left at its initial deny-all state. This is safe (no traffic
 * flows) and the empty-set path is covered by tests so a future refactor
 * that accidentally unbinds every contributor still surfaces clearly.
 *
 * # Why a class, not an init-time helper
 *
 * Holding the collector job in [collectorJob] gives tests a synchronous
 * signal that the subscription is live ([isCollecting]) and lets future
 * shutdown paths cancel cleanly without yanking the whole [scope].
 *
 * @param gateway The singleton [NetworkGateway] to drive. Constructor-injected
 *   so tests can swap a [FakeNetworkGateway].
 * @param contributors Every [NetworkPolicyContributor] currently registered.
 *   Hilt's `@JvmSuppressWildcards` annotation on the call site keeps the
 *   `Set<NetworkPolicyContributor>` type stable across Kotlin/Java boundaries
 *   for multibindings; consumers in `:network` (tests) can pass any `Set`.
 * @param scope Long-lived coroutine scope (typically `@ApplicationScope`)
 *   that owns the combine collector for the process lifetime.
 */
@Singleton
class NetworkPolicyAggregator @Inject constructor(
	private val gateway: NetworkGateway,
	private val contributors: Set<@JvmSuppressWildcards NetworkPolicyContributor>,
	@ApplicationScope private val scope: CoroutineScope,
) {

	private val collectorJob: Job? = startCollector()

	/**
	 * `true` once the combine collector is running. Always `false` when zero
	 * contributors are registered (the degenerate, safe path). Useful in
	 * tests that need to assert the aggregator wired up at all without
	 * waiting on a [NetworkGateway] state change.
	 */
	val isCollecting: Boolean
		get() = collectorJob?.isActive == true

	private fun startCollector(): Job? {
		if (contributors.isEmpty()) {
			// kotlinx.coroutines.flow.combine over an empty Iterable never
			// emits, so launching a collector would leave the gateway at its
			// initial deny-all state with no observable signal. Make the
			// no-contributor case explicit instead of relying on combine's
			// silent never-emit behaviour. Gateway stays disabled / EMPTY by
			// its own default — exactly what we'd publish anyway.
			return null
		}
		val flows = contributors.map { it.contribution }
		return scope.launch {
			combine(flows) { snapshot -> aggregate(snapshot.toList()) }
				.collect { (enabled, policy) ->
					// Publish policy BEFORE arming the kill switch so the
					// interceptor chain sees the right allowlist on the very
					// first request that flows after a 0→1 transition.
					// Conversely on a 1→0 transition publishing policy first
					// (now EMPTY) means in-flight requests that race the
					// kill switch are also rejected by the allowlist gate —
					// belt-and-braces.
					gateway.setPolicy(policy)
					gateway.setEnabled(enabled)
				}
		}
	}

	private fun aggregate(
		contributions: List<NetworkPolicyContribution>,
	): Pair<Boolean, NetworkPolicy> {
		val active = contributions.filterIsInstance<NetworkPolicyContribution.Active>()
		if (active.isEmpty()) return false to NetworkPolicy.EMPTY
		val allHosts = active.flatMapTo(HashSet()) { it.allowedHosts }
		val maxLimit = active.maxOf { it.perHostRateLimit }
		val maxWindow = active.maxOf { it.perHostRateWindowMs }
		return true to NetworkPolicy(
			allowedHosts = allHosts,
			perHostRateLimit = maxLimit,
			perHostRateWindowMs = maxWindow,
		)
	}
}
