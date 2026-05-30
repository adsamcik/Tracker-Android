package com.adsamcik.tracker.stats.api

import com.adsamcik.tracker.shared.base.data.Location
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only adapter that exposes the foreground tracker's existing location
 * stream to other features (mini-games, dashboards, live previews) without
 * forcing them to depend on `:tracker` directly or open a second
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
 *  - Implementations live in `:tracker` so the cross-cutting interface here
 *    stays free of tracker internals and module boundaries are preserved
 *    (`:game` does not, and must not, depend on `:tracker`).
 *
 * Consumers should:
 *  1. Observe [isActiveFlow] (or snapshot [isActive]) to decide whether to
 *     subscribe to [locations] or fall back to their own location source.
 *  2. Treat [locations] as a cold-share view of the underlying StateFlow:
 *     cancelling the collector does not stop the tracker, it just stops
 *     forwarding fixes to that collector.
 *  3. Not attempt to write to or steer the tracker through this interface —
 *     it is intentionally read-only.
 */
interface TrackerLiveLocationFeed {

	/**
	 * Hot flow that mirrors `TrackerServiceController.isServiceRunningFlow`.
	 * `true` when the tracker is currently subscribed to FusedLocationProvider
	 * and emitting fixes on [locations]; `false` when no session is running.
	 */
	val isActiveFlow: StateFlow<Boolean>

	/**
	 * Snapshot accessor for [isActiveFlow]. Cheaper than a one-shot collect when
	 * the caller just wants a routing decision at subscription time.
	 */
	val isActive: Boolean

	/**
	 * Cold flow of [Location] fixes captured by the running tracker. Emits only
	 * when a session is active and a fresh fix arrives — never replays stale
	 * snapshots. Null locations from the underlying CollectionData (sessions
	 * that have not yet produced a fix) are filtered out.
	 */
	fun locations(): Flow<Location>
}
