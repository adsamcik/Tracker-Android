package com.adsamcik.tracker.shared.base.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Readonly tracking session data
 */
@Entity(
		tableName = "tracker_session", foreignKeys = [ForeignKey(
		entity = SessionActivity::class,
		parentColumns = ["id"],
		childColumns = ["session_activity_id"],
		onDelete = ForeignKey.SET_NULL,
		onUpdate = ForeignKey.NO_ACTION
)], indices = [Index("session_activity_id")]
)
@Suppress("LongParameterList")
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

	open var start: Long = start

	open var end: Long = end

	@ColumnInfo(name = "user_initiated")
	open var isUserInitiated: Boolean = isUserInitiated

	open var collections: Int = collections

	@ColumnInfo(name = "distance")
	open var distanceInM: Float = distanceInM

	@ColumnInfo(name = "distance_on_foot")
	open var distanceOnFootInM: Float = distanceOnFootInM

	@ColumnInfo(name = "distance_in_vehicle")
	open var distanceInVehicleInM: Float = distanceInVehicleInM

	open var steps: Int = steps

	@ColumnInfo(name = "session_activity_id")
	open var sessionActivityId: Long? = sessionActivityId

	companion object {
		/**
		 * Action used when new session starts. Can be called multiple times for a single session
		 * if the session is resumed.
		 */
		const val ACTION_SESSION_STARTED: String = "com.adsamcik.tracker.intent.action.SESSION_START"

		/**
		 * Action used when session stops. Can be called multiple times for a single session
		 * if the session is resumed.
		 */
		const val ACTION_SESSION_ENDED: String = "com.adsamcik.tracker.intent.action.SESSION_END"

		/**
		 * Action used when session is final. Session can no longer be resumed.
		 */
		const val ACTION_SESSION_FINAL: String = "com.adsamcik.tracker.intent.action.SESSION_FINAL"
		const val RECEIVER_SESSION_ID: String = "id"
		const val RECEIVER_SESSION_IS_NEW: String = "isNew"
		const val RECEIVER_SESSION_RESUME_TIMEOUT: String = "resumeTimeout"
		const val BROADCAST_PERMISSION: String = "com.adsamcik.tracker.permission.TRACKER"
	}
}

/**
 * Mutable tracker session data
 */
@Suppress("LongParameterList")
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
		TrackerSession(
				id,
				start,
				end,
				isUserInitiated,
				collections,
				distanceInM,
				distanceOnFootInM,
				distanceInVehicleInM,
				steps,
				sessionActivityId
		) {

	override var id: Long
		get() = super.id
		set(value) {
			super.id = value
		}

	override var start: Long
		get() = super.start
		set(value) {
			super.start = value
		}

	override var end: Long
		get() = super.end
		set(value) {
			super.end = value
		}

	override var isUserInitiated: Boolean
		get() = super.isUserInitiated
		set(value) {
			super.isUserInitiated = value
		}

	override var collections: Int
		get() = super.collections
		set(value) {
			super.collections = value
		}

	override var distanceInM: Float
		get() = super.distanceInM
		set(value) {
			super.distanceInM = value
		}

	override var distanceInVehicleInM: Float
		get() = super.distanceInVehicleInM
		set(value) {
			super.distanceInVehicleInM = value
		}

	override var distanceOnFootInM: Float
		get() = super.distanceOnFootInM
		set(value) {
			super.distanceOnFootInM = value
		}

	override var steps: Int
		get() = super.steps
		set(value) {
			super.steps = value
		}

	override var sessionActivityId: Long?
		get() = super.sessionActivityId
		set(value) {
			super.sessionActivityId = value
		}

	constructor(start: Long, isUserInitiated: Boolean) : this(
			0,
			start,
			start,
			isUserInitiated = isUserInitiated,
			collections = 0,
			distanceInM = 0f,
			distanceInVehicleInM = 0f,
			distanceOnFootInM = 0f,
			steps = 0
	)

	constructor(session: TrackerSession) : this(
			session.id,
			session.start,
			session.end,
			session.isUserInitiated,
			session.collections,
			session.distanceInM,
			session.distanceOnFootInM,
			session.distanceInVehicleInM,
			session.steps
	)
}

