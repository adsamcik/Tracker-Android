package com.adsamcik.tracker.common.data

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.WorkerThread
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.adsamcik.tracker.common.database.AppDatabase

@Entity(tableName = "activity", indices = [Index("name")])
data class SessionActivity(
		@PrimaryKey(autoGenerate = true) var id: Long = 0,
		val name: String,
		val iconName: String? = null
) {

	constructor(context: Context, nativeSessionActivity: NativeSessionActivity) :
			this(
					nativeSessionActivity.id,
					context.getString(nativeSessionActivity.nameRes),
					nativeSessionActivity.iconName
			)

	fun getIcon(context: Context): Drawable {
		val resources = context.resources
		val drawableName = iconName
				?: com.adsamcik.tracker.common.R.drawable::ic_help_white.name
		val drawableId = resources.getIdentifier(drawableName, "drawable", context.packageName)

		if (drawableId == 0) throw NullPointerException("Resource with name $drawableName not found")

		return resources.getDrawable(drawableId, context.theme)
	}

	companion object {
		val UNKNOWN get() = SessionActivity(0L, "", null)

		@WorkerThread
		fun getAll(context: Context): List<SessionActivity> {
			val database = AppDatabase.database(context)
			val activityDao = database.activityDao()
			val mutableList = mutableListOf<SessionActivity>()

			mutableList.addAll(activityDao.getAllUser())
			mutableList.addAll(NativeSessionActivity.values().map { SessionActivity(context, it) })
			return mutableList
		}
	}
}

