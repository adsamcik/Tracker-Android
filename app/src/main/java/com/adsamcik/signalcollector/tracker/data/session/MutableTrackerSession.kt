package com.adsamcik.signalcollector.tracker.data.session

import com.adsamcik.signalcollector.common.data.TrackerSession

class MutableTrackerSession(
		id: Long = 0,
		start: Long,
		end: Long,
		isUserInitiated: Boolean,
		collections: Int,
		distanceInM: Float,
		distanceOnFootInM: Float,
		distanceInVehicleInM: Float,
		steps: Int,
		sessionActivityId: Long? = null) : TrackerSession(id, start, end, isUserInitiated, collections, distanceInM, distanceOnFootInM, distanceInVehicleInM, steps, sessionActivityId) {

	override var id: Long
		get() = super.id
		public set(value) {
			super.id = value
		}

	override var isUserInitiated: Boolean
		get() = super.isUserInitiated
		public set(value) {
			super.isUserInitiated = value
		}

	override var collections: Int
		get() = super.collections
		public set(value) {
			super.collections = value
		}

	override var distanceInM: Float
		get() = super.distanceInM
		public set(value) {
			super.distanceInM = value
		}

	override var distanceInVehicleInM: Float
		get() = super.distanceInVehicleInM
		public set(value) {
			super.distanceInVehicleInM = value
		}

	override var distanceOnFootInM: Float
		get() = super.distanceOnFootInM
		public set(value) {
			super.distanceOnFootInM = value
		}

	override var start: Long
		get() = super.start
		public set(value) {
			super.start = value
		}

	override var end: Long
		get() = super.end
		public set(value) {
			super.end = value
		}

	override var steps: Int
		get() = super.steps
		public set(value) {
			super.steps = value
		}

	override var sessionActivityId: Long?
		get() = super.sessionActivityId
		public set(value) {
			super.sessionActivityId = value
		}

	constructor(start: Long, isUserInitiated: Boolean) : this(0, start, start, isUserInitiated, 0, 0f, 0f, 0f, 0)

	constructor(session: TrackerSession) : this(session.id,
			session.start,
			session.end,
			session.isUserInitiated,
			session.collections,
			session.distanceInM,
			session.distanceOnFootInM,
			session.distanceInVehicleInM,
			session.steps)
}