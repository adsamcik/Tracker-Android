package com.adsamcik.tracker.stats.api.presenter

import kotlinx.coroutines.flow.Flow

/**
 * Base interface for Molecule-style presenters.
 * Produces a stream of UI state from a stream of UI events.
 *
 * @param Event UI events (user actions)
 * @param State UI state produced by this presenter
 */
interface Presenter<Event, State> {
	fun present(events: Flow<Event>): Flow<State>
}
