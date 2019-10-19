package com.adsamcik.tracker.map.introduction

import android.view.View
import androidx.fragment.app.FragmentActivity
import com.adsamcik.tracker.common.extension.dp
import com.adsamcik.tracker.common.introduction.Introduction
import com.adsamcik.tracker.map.R
import com.takusemba.spotlight.SimpleTarget
import com.takusemba.spotlight.shapes.Circle
import com.takusemba.spotlight.shapes.RoundedRectangle

class MapIntroduction : Introduction() {
	override val key: String = "map_tips"

	override fun getTargets(activity: FragmentActivity): Collection<com.takusemba.spotlight.Target> {
		activity.run {
			val buttonData = SimpleTarget.ButtonData(
					getString(com.adsamcik.tracker.common.R.string.next_part)
			) { _, spotlight ->
				spotlight.next()
			}

			val dp8 = RECTANGLE_RADIUS_DP.dp.toFloat()

			var target = findViewById<View>(R.id.map_sheet_drag_area)
			val mapMenuButtonTarget = SimpleTarget.Builder(this)
					.setPoint(target.x + target.pivotX, target.y + target.pivotY)
					.setTitle(getString(R.string.tips_map_sheet_title))
					.addButtonData(buttonData)
					.addButtonData(SimpleTarget.ButtonData(
							resources.getString(
									com.adsamcik.tracker.common.R.string.skip_introduction
							)
					) { _, spotlight ->
						spotlight.finishSpotlight()
					})
					.setShape(RoundedRectangle(target, dp8, dp8))
					.setDescription(getString(R.string.tips_map_sheet_description))
					.build()

			target = findViewById<View>(R.id.map_search_parent)
			val searchTarget = SimpleTarget.Builder(this)
					.setPoint(target.x + target.pivotX, target.y + target.pivotY)
					.setTitle(getString(R.string.tips_map_search_title))
					.addButtonData(buttonData)
					.setShape(RoundedRectangle(target, dp8, dp8))
					.setDescription(getString(R.string.tips_map_search_description)).build()

			target = findViewById<View>(R.id.button_map_my_location)
			val myLocationButtonTarget = SimpleTarget.Builder(this)
					.setPoint(target.x + target.pivotX, target.y + target.pivotY)
					.setTitle(getString(R.string.tips_map_my_location_title))
					.addButtonData(buttonData)
					.setShape(Circle(target))
					.setDescription(getString(R.string.tips_map_my_location_description))
					.build()

			target = findViewById<View>(R.id.button_map_date_range)
			val dateRangeTarget = SimpleTarget.Builder(this)
					.setPoint(target.x + target.pivotX, target.y + target.pivotY)
					.setTitle(getString(R.string.tips_map_date_range_title))
					.addButtonData(buttonData)
					.setShape(Circle(target))
					.setDescription(getString(R.string.tips_map_date_range_description))
					.build()

			return listOf(
					mapMenuButtonTarget,
					searchTarget,
					myLocationButtonTarget,
					dateRangeTarget
			)
		}
	}

	companion object {
		private const val RECTANGLE_RADIUS_DP = 8
	}

}

