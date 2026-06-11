package com.adsamcik.tracker.shared.base.data

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.adsamcik.tracker.shared.base.database.AppDatabase

// NOTE: SessionActivity is a reference-data entity (activity type definitions), not a per-session
// record. It is actively managed via UI (SessionActivityActivityCompose) and seeded by
// NativeSessionActivity. TrackerSession has a FK to this table. ActivitySnapshot does NOT replace
// this entity — ActivitySnapshot records raw transitions, while SessionActivity defines types.
// No deprecation needed; this entity serves a distinct purpose from the sessionless architecture.
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

	fun getIcon(context: Context): Drawable? {
		val resources = context.resources
		val drawableName = iconName
				?: com.adsamcik.tracker.shared.base.R.drawable::ic_help_white.name
		val drawableId = resources.getIdentifier(drawableName, "drawable", context.packageName)

		if (drawableId == 0) throw NullPointerException("Resource with name $drawableName not found")

		return ResourcesCompat.getDrawable(resources, drawableId, context.theme)
	}

	companion object {
		/**
		 * Unknown activity value. Default for many applications.
		 */
		val UNKNOWN: SessionActivity get() = SessionActivity(0L, "", null)

		suspend fun getAll(context: Context): List<SessionActivity> {
			val database = AppDatabase.database(context)
			val activityDao = database.activityDao()
			val mutableList = mutableListOf<SessionActivity>()

			mutableList.addAll(activityDao.getAllUser())
			mutableList.addAll(NativeSessionActivity.entries.map { SessionActivity(context, it) })
			return mutableList
		}
	}
}
