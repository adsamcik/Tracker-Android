package com.adsamcik.tracker.tracker.data.session

import com.adsamcik.tracker.shared.base.data.TrackerSession

/**
 * Maps the engine's mutable Room-backed session into the immutable API contract.
 *
 * Keeping this adapter in `:tracker:engine` prevents persistence entities from
 * crossing the `:tracker:api` boundary.
 */
internal fun TrackerSession.toSnapshot(): TrackerSessionSnapshot = TrackerSessionSnapshot(
	id = id,
	start = start,
	end = end,
	isUserInitiated = isUserInitiated,
	collections = collections,
	distanceInM = distanceInM,
	distanceOnFootInM = distanceOnFootInM,
	distanceInVehicleInM = distanceInVehicleInM,
	steps = steps,
	sessionActivityId = sessionActivityId,
)
