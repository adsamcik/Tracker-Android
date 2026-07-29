package com.adsamcik.tracker.stats.api

import com.adsamcik.tracker.shared.model.Location
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only adapter that exposes the foreground tracker's existing location
 * stream to other features (mini-games, dashboards, live previews) without
 * forcing them to depend on `:tracker:engine` directly or open a second
 * FusedLocationProviderClient subscription.
 *
 * Why this interface exists:
 *
 *  - The tracker service, when running, already maintains a precise live GPS
 *    subscription via the producer pipeline; every fix it receives is published
 *    on `TrackerServiceController.collectionDataFlow`.
 *  - Without this feed, every secondary consumer (mini-game session, dashboard
 *    live tile, debug overlay) would open its own FusedLocationProviderClient
 *    request. Running two concurrent location subscriptions doubles the radio
 *    cost and produces duplicate framework wake-ups for no extra information.
 *  - Implementations live in `:tracker:engine` so the cross-cutting interface here
 *    stays free of tracker internals and module boundaries are preserved
 *    (`:feature:game` does not, and must not, depend on `:tracker:engine`).
 *
 * Consumers should:
 *  1. Observe [isActiveFlow] (or snapshot [isActive]) to decide whether to
 *     subscribe to [locations] or fall back to their own location source.
 *  2. Treat [locations] as a hot view of the underlying tracker StateFlow:
 *     new collectors may receive the latest available fix immediately
 *     (StateFlow-backed replay of 1, then distinct-filtered). Cancelling a
 *     collector does not stop the tracker; it just stops forwarding fixes
 *     to that collector.
 *  3. Treat the first emission as "the most recent known fix", not "a
 *     freshly-acquired fix at subscription time". If you need a strictly
 *     post-subscription fix (e.g. mini-game start time), drop the first
 *     emission or stamp/dedupe by timestamp on the consumer side.
 *  4. Not attempt to write to or steer the tracker through this interface —
 *     it is intentionally read-only.
 */
interface TrackerLiveLocationFeed {

	/**
	 * Hot StateFlow that mirrors `TrackerServiceController.isServiceRunningFlow`.
	 * `true` when the tracker is currently subscribed to FusedLocationProvider
	 * and emitting fixes on [locations]; `false` when no session is running.
	 * New collectors receive the current value immediately.
	 */
	val isActiveFlow: StateFlow<Boolean>

	/**
	 * Snapshot accessor for [isActiveFlow]. Cheaper than a one-shot collect when
	 * the caller just wants a routing decision at subscription time.
	 */
	val isActive: Boolean

	/**
	 * Hot flow of [Location] fixes captured by the running tracker, adapted
	 * from the underlying tracker collection-snapshot StateFlow.
	 *
	 * Emission semantics:
	 *  - **Replay of the latest fix.** A new collector receives the most
	 *    recently published non-null [Location] (if any) immediately on
	 *    subscription — this comes from the StateFlow replay of 1 upstream.
	 *    There is no per-subscriber `drop(1)` or freshness gate here. If a
	 *    consumer must not consume a stale fix, it is responsible for its
	 *    own gating.
	 *  - **Null-filtering.** Null collection snapshots (between sessions)
	 *    and null snapshot location values (sessions that
	 *    have not yet produced a fix) are dropped.
	 *  - **Distinct-until-changed by Location equality.** Repeated identical
	 *    `Location` values are suppressed, including immediately after a
	 *    replayed first emission, so a collector that resubscribes while the
	 *    tracker is idle will not see repeated copies of the same last fix.
	 *  - **Lifetime tied to the tracker, not the collector.** Cancelling
	 *    the collector does not stop the tracker, and stopping the tracker
	 *    does not complete this flow — emissions simply pause until the next
	 *    session publishes a new non-null fix.
	 */
	fun locations(): Flow<Location>
}
