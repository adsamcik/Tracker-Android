package com.adsamcik.signalcollector.app

import android.graphics.PointF
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.extension.dp
import com.adsamcik.signalcollector.common.introduction.Introduction
import com.takusemba.spotlight.SimpleTarget
import com.takusemba.spotlight.Target
import com.takusemba.spotlight.shapes.Circle
import com.takusemba.spotlight.shapes.RoundedRectangle

class HomeIntroduction : Introduction() {
	override val key: String = "home_tips"

	override fun getTargets(activity: FragmentActivity): Collection<Target> {
		val targetList = ArrayList<Target>(5)
		activity.apply {
			val resources = resources
			//todo add next button to library
			val buttonData = SimpleTarget.ButtonData(resources.getString(com.adsamcik.signalcollector.common.R.string.next_part)) { _, spotlight ->
				spotlight.next()
			}

			val welcome = SimpleTarget.Builder(this)
					.setTitle(resources.getString(R.string.tutorial_welcome_title))
					.addButtonData(SimpleTarget.ButtonData(resources.getString(com.adsamcik.signalcollector.common.R.string.skip_tips)) { _, spotlight ->
						spotlight.finishSpotlight()
					})
					.addButtonData(buttonData)
					.setShape(Circle(PointF(0f, 0f), 0f))
					.setDescription(resources.getString(R.string.tutorial_welcome_description)).build()

			//var radius = Math.sqrt(Math.pow(button_settings.height.toDouble(), 2.0) + Math.pow(button_settings.width.toDouble(), 2.0)) / 2
			// var point = PointF()
			var target = findViewById<View>(R.id.button_settings)
			val settingsButtonTarget = SimpleTarget.Builder(this)
					.setPoint(target.x + target.pivotX, target.y + target.pivotY)
					.setTitle(resources.getString(R.string.tutorial_settings_title))
					.addButtonData(buttonData)
					.setShape(Circle(target))
					.setDescription(resources.getString(R.string.tutorial_settings_description))
					.build()

			target = findViewById<View>(R.id.button_stats)
			//radius = Math.sqrt(Math.pow(button_stats.height.toDouble(), 2.0) + Math.pow(button_stats.width.toDouble(), 2.0)) / 2
			val statsButtonTarget = SimpleTarget.Builder(this)
					.setPoint(target.x + target.pivotX, target.y + target.pivotY)
					.setTitle(resources.getString(R.string.tutorial_stats_title))
					.addButtonData(buttonData)
					.setShape(Circle(target))
					.setDescription(resources.getString(R.string.tutorial_stats_description))
					.build()

			target = findViewById<View>(R.id.button_game)
			//radius = Math.sqrt(Math.pow(button_activity.height.toDouble(), 2.0) + Math.pow(button_activity.width.toDouble(), 2.0)) / 2
			val activitiesButtonTarget = SimpleTarget.Builder(this)
					.setPoint(target.x + target.pivotX, target.y + target.pivotY)
					.setTitle(resources.getString(R.string.tutorial_activity_title))
					.addButtonData(buttonData)
					.setShape(Circle(target))
					.setDescription(resources.getString(R.string.tutorial_activity_description))
					.build()

			target = findViewById<View>(R.id.button_map)
			val mapButtonTarget = SimpleTarget.Builder(this)
					.setPoint(target.x + target.pivotX, target.y + target.pivotY)
					.setTitle(resources.getString(R.string.tutorial_map_title))
					.addButtonData(buttonData)
					.setShape(RoundedRectangle(target, 8.dp.toFloat(), target.height.toFloat()))
					.setDescription(resources.getString(R.string.tutorial_map_description))
					.build()

			with(targetList) {
				add(welcome)
				add(settingsButtonTarget)
				add(mapButtonTarget)
				add(statsButtonTarget)
				add(activitiesButtonTarget)
			}
		}

		return targetList
	}

}