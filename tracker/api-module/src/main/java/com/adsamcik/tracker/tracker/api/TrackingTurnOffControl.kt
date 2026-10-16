package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.tracker.resilience.TrackingTurnOffReservation

/** A user stop keeps source choices and retained observations, but closes every capture purpose. */
enum class TrackingTurnOffResult {
	COMPLETE,
	DURABLY_OFF_CLEANUP_PENDING,
	SUPERSEDED_BY_NEW_START,
	UNAVAILABLE,
}

/**
 * Coordinates durable Off reservation, source fencing and acknowledged owner closure across every UI entry
 * point.
 */
interface TrackingTurnOffController {
	/** Null means no durable Off/STOP command could be reserved. */
	suspend fun reserveAndFence(expectedStartGeneration: Long? = null): TrackingTurnOffReservation?

	/** Closes owners only while this exact Off still holds the command gate. */
	suspend fun closeOwnersAfterSessionStop(exitId: String): Boolean?
}

/** Distinguishes an explicit all-source stop from Android's user-exit recovery receipt. */
const val TRACKING_TURN_OFF_EXIT_PREFIX = "turn-off:"
