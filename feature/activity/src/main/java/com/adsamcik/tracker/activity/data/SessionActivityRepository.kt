package com.adsamcik.tracker.activity.data

/**
 * Immutable activity type presented by the feature.
 */
data class SessionActivityItem(
	val id: Long,
	val name: String,
	val iconName: String? = null,
)

data class CreateSessionActivityCommand(
	val name: String,
	val iconName: String? = null,
)

data class UpdateSessionActivityCommand(
	val id: Long,
	val name: String,
	val iconName: String? = null,
)

/**
 * Feature-facing persistence boundary for user-created session activities.
 */
interface SessionActivityRepository {
	suspend fun getActivities(): List<SessionActivityItem>
	suspend fun delete(activityId: Long)
	suspend fun create(command: CreateSessionActivityCommand)
	suspend fun update(command: UpdateSessionActivityCommand)
}
