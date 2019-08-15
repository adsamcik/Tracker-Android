package com.adsamcik.tracker.common.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "tracker_session", foreignKeys = [ForeignKey(entity = SessionActivity::class,
		parentColumns = ["id"],
		childColumns = ["session_activity_id"],
		onDelete = ForeignKey.SET_NULL,
		onUpdate = ForeignKey.NO_ACTION)], indices = [Index("session_activity_id")])
open class TrackerSession(
		id: Long = 0,
		start: Long = 0,
		end: Long = 0,
		isUserInitiated: Boolean = false,
		collections: Int = 0,
		distanceInM: Float = 0f,
		distanceOnFootInM: Float = 0f,
		distanceInVehicleInM: Float = 0f,
		steps: Int = 0,
		sessionActivityId: Long? = null
) {

	@PrimaryKey(autoGenerate = true)
	open var id: Long = id
		protected set

	open var start: Long = start
		protected set

	open var end: Long = end
		protected set

	@ColumnInfo(name = "user_initiated")
	open var isUserInitiated: Boolean = isUserInitiated
		protected set

	open var collections: Int = collections
		protected set

	@ColumnInfo(name = "distance")
	open var distanceInM: Float = distanceInM
		protected set

	@ColumnInfo(name = "distance_on_foot")
	open var distanceOnFootInM: Float = distanceOnFootInM
		protected set

	@ColumnInfo(name = "distance_in_vehicle")
	open var distanceInVehicleInM: Float = distanceInVehicleInM
		protected set

	open var steps: Int = steps
		protected set

	@ColumnInfo(name = "session_activity_id")
	open var sessionActivityId: Long? = sessionActivityId
		protected set

	companion object {
		const val RECEIVER_SESSION_STARTED: String = "tracker.intent.action.SESSION_START"
		const val RECEIVER_SESSION_ENDED: String = "tracker.intent.action.SESSION_END"
		const val RECEIVER_SESSION_ID: String = "id"
	}
}

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
		sessionActivityId: Long? = null
) :
		TrackerSession(id,
				start,
				end,
				isUserInitiated,
				collections,
				distanceInM,
				distanceOnFootInM,
				distanceInVehicleInM,
				steps,
				sessionActivityId) {

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

