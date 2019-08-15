package com.adsamcik.signalcollector.map.fragment

import android.view.View
import androidx.fragment.app.FragmentActivity
import com.adsamcik.signalcollector.common.extension.dp
import com.adsamcik.signalcollector.common.introduction.Introduction
import com.adsamcik.signalcollector.map.R
import com.takusemba.spotlight.SimpleTarget
import com.takusemba.spotlight.shapes.Circle
import com.takusemba.spotlight.shapes.RoundedRectangle

class MapIntroduction : Introduction() {
	override val key: String = "map_tips"

	override fun getTargets(activity: FragmentActivity): Collection<com.takusemba.spotlight.Target> {
		activity.run {
			val buttonData = SimpleTarget.ButtonData(
					getString(com.adsamcik.signalcollector.common.R.string.next_part)) { _, spotlight ->
				spotlight.next()
			}

			var target = findViewById<View>(R.id.layout_map_controls)
			val searchTarget = SimpleTarget.Builder(this)
					.setPoint(target.x + target.pivotX, target.y + target.pivotY)
					.setTitle(getString(R.string.tips_map_search_title))
					.addButtonData(SimpleTarget.ButtonData(resources.getString(
							com.adsamcik.signalcollector.common.R.string.skip_tips)) { _, spotlight ->
						spotlight.finishSpotlight()
					})
					.addButtonData(buttonData)
					.setShape(RoundedRectangle(target, 8.dp.toFloat(), 8.dp.toFloat()))
					.setDescription(getString(R.string.tips_map_search_description)).build()

			target = findViewById<View>(R.id.button_map_my_location)
			//radius = Math.sqrt(Math.pow(button_stats.height.toDouble(), 2.0) + Math.pow(button_stats.width.toDouble(), 2.0)) / 2
			val myLocationButtonTarget = SimpleTarget.Builder(this)
					.setPoint(target.x + target.pivotX, target.y + target.pivotY)
					.setTitle(getString(R.string.tips_map_my_location_title))
					.addButtonData(buttonData)
					.setShape(Circle(target))
					.setDescription(getString(R.string.tips_map_my_location_description))
					.build()

			target = findViewById<View>(R.id.map_sheet_drag_area)
			//radius = Math.sqrt(Math.pow(button_activity.height.toDouble(), 2.0) + Math.pow(button_activity.width.toDouble(), 2.0)) / 2
			val mapMenuButtonTarget = SimpleTarget.Builder(this)
					.setPoint(target.x + target.pivotX, target.y + target.pivotY)
					.setTitle(getString(R.string.tips_map_overlay_title))
					.addButtonData(buttonData)
					.setShape(RoundedRectangle(target, 8.dp.toFloat(), 8.dp.toFloat()))
					.setDescription(getString(R.string.tips_map_overlay_description))
					.build()

			return listOf(searchTarget, myLocationButtonTarget, mapMenuButtonTarget)
		}
	}

}
