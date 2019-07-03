package com.adsamcik.signalcollector.common.data

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.WorkerThread
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.adsamcik.signalcollector.common.database.AppDatabase

//todo add icon
@Entity(tableName = "activity", indices = [Index("name")])
data class SessionActivity(
		@PrimaryKey(autoGenerate = true) var id: Long,
		var name: String,
		val iconName: String?
) {

	constructor(context: Context, nativeSessionActivity: NativeSessionActivity):
			this(nativeSessionActivity.id,
					context.getString(nativeSessionActivity.nameRes),
					nativeSessionActivity.iconName)

	fun getIcon(context: Context): Drawable {
		val resources = context.resources
		val drawableName = iconName	?: com.adsamcik.signalcollector.common.R.drawable::ic_baseline_device_unknown.name
		val drawableId = resources.getIdentifier(drawableName, "drawable", context.packageName)

		if (drawableId == 0) throw NullPointerException("Resource with name $drawableName not found")

		return resources.getDrawable(drawableId, context.theme)
	}

	companion object {
		val empty get() = SessionActivity(0L, "", null)

		@WorkerThread
		fun getAll(context: Context): List<SessionActivity> {
			val database = AppDatabase.getDatabase(context)
			val activityDao = database.activityDao()
			val mutableList = mutableListOf<SessionActivity>()

			mutableList.addAll(activityDao.getAll())
			mutableList.addAll(NativeSessionActivity.values().map { SessionActivity(context, it) })
			return mutableList
		}
	}
}