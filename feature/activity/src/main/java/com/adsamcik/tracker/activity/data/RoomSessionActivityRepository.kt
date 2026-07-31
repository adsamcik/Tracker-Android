package com.adsamcik.tracker.activity.data

import android.content.Context
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.database.dao.ActivityDao
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

@Singleton
internal class RoomSessionActivityRepository @Inject constructor(
	@ApplicationContext private val context: Context,
	private val activityDao: ActivityDao,
	private val dispatchers: DispatchersProvider,
) : SessionActivityRepository {
	override suspend fun getActivities(): List<SessionActivityItem> =
		withContext(dispatchers.io) {
			activityDao.getAllUser().map { it.toFeatureItem() } +
				NativeSessionActivity.entries.map { activity ->
					SessionActivityItem(
						id = activity.id,
						name = context.getString(activity.nameRes),
						iconName = activity.iconName,
					)
				}
		}

	override suspend fun delete(activityId: Long) {
		withContext(dispatchers.io) {
			activityDao.delete(activityId)
		}
	}

	override suspend fun create(command: CreateSessionActivityCommand) {
		withContext(dispatchers.io) {
			activityDao.insert(
				SessionActivity(
					name = command.name,
					iconName = command.iconName,
				),
			)
		}
	}

	override suspend fun update(command: UpdateSessionActivityCommand) {
		withContext(dispatchers.io) {
			activityDao.update(
				SessionActivity(
					id = command.id,
					name = command.name,
					iconName = command.iconName,
				),
			)
		}
	}

	private fun SessionActivity.toFeatureItem() = SessionActivityItem(
		id = id,
		name = name,
		iconName = iconName,
	)
}
