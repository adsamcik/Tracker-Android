package com.adsamcik.signalcollector.common.data

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

//todo add icon
@Entity(tableName = "activity", indices = [Index("name")])
data class SessionActivity(
		@PrimaryKey(autoGenerate = true) var id: Long,
		var name: String,
		val iconName: String?
) {

	fun getIcon(context: Context): Drawable {
		val resources = context.resources
		val drawableName = iconName	?: com.adsamcik.signalcollector.common.R.drawable::ic_baseline_device_unknown.name
		val drawableId = resources.getIdentifier(drawableName, "drawable", context.packageName)

		if (drawableId == 0) throw NullPointerException("Resource with name $drawableName not found")

		return resources.getDrawable(drawableId, context.theme)
	}
}