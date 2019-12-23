package com.adsamcik.tracker.map.introduction

import android.graphics.PointF
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.adsamcik.tracker.shared.base.assist.DisplayAssist
import com.adsamcik.tracker.shared.base.extension.dp
import com.adsamcik.tracker.shared.utils.introduction.Introduction
import com.adsamcik.tracker.map.R
import com.takusemba.spotlight.SimpleTarget
import com.takusemba.spotlight.Target
import com.takusemba.spotlight.shapes.RoundedRectangle

class MapSheetHiddenIntroduction : Introduction() {
	override val key: String = "map_sheet_hidden"

	override fun getTargets(activity: FragmentActivity): Collection<Target> {
		activity.run {
			val buttonData = SimpleTarget.ButtonData(
					getString(com.adsamcik.tracker.shared.base.R.string.next_part)
			) { _, spotlight ->
				spotlight.next()
			}

			val target = findViewById<View>(R.id.container_map)
			val center = PointF(target.x + target.pivotX, target.y + target.pivotY)

			val realArea = DisplayAssist.getRealArea(this)
			val usableArea = DisplayAssist.getUsableArea(this)
			val screenLocation = IntArray(2)
			findViewById<View>(com.adsamcik.tracker.R.id.button_map)
					.getLocationInWindow(screenLocation)

			val offsetY = (realArea.y - usableArea.y) / 2f

			val height = usableArea.y - screenLocation[1] * 2f
			val width = usableArea.x.toFloat()

			val myLocationButtonTarget = SimpleTarget.Builder(this)
					.setTitle(getString(R.string.tips_map_hidden_sheet_title))
					.setDescription(getString(R.string.tips_map_hidden_sheet_description))
					.setPoint(center)
					.addButtonData(buttonData)
					.setShape(
							RoundedRectangle(
									PointF(center.x, center.y + offsetY),
									width,
									height,
									RECTANGLE_RADIUS.dp.toFloat()
							)
					)
					.build()
			return listOf(myLocationButtonTarget)
		}
	}

	companion object {
		private const val RECTANGLE_RADIUS = 16
	}
}
