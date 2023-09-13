package com.adsamcik.tracker.app

import androidx.appcompat.app.AppCompatActivity
import android.content.res.Resources
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.utils.introduction.Introduction
import com.takusemba.spotlight.SimpleTarget
import com.takusemba.spotlight.Target
import com.takusemba.spotlight.shapes.Circle
import com.takusemba.spotlight.shapes.RoundedRectangle

/**
 * Introduction on home screen.
 */
class HomeIntroduction : Introduction() {
	override val key: String = "home_tips"

	private fun createSettingsTarget(
        activity: AppCompatActivity,
        resources: Resources,
        buttonData: SimpleTarget.ButtonData
	): Target {
		val target = activity.findViewById<View>(R.id.button_settings)
		return SimpleTarget.Builder(activity)
				.setPoint(target.x + target.pivotX, target.y + target.pivotY)
				.setTitle(resources.getString(R.string.tutorial_settings_title))
				.addButtonData(buttonData)
				.addButtonData(SimpleTarget.ButtonData(
						resources.getString(
								com.adsamcik.tracker.shared.base.R.string.skip_introduction
						)
				) { _, spotlight ->
					spotlight.finishSpotlight()
				})
				.setShape(Circle(target))
				.setDescription(resources.getString(R.string.tutorial_settings_description))
				.build()
	}


	private fun createMapTarget(
        activity: AppCompatActivity,
        resources: Resources,
        buttonData: SimpleTarget.ButtonData
	): Target {
		val target = activity.findViewById<View>(R.id.button_map)
		return SimpleTarget.Builder(activity)
				.setPoint(target.x + target.pivotX, target.y + target.pivotY)
				.setTitle(resources.getString(R.string.tutorial_map_title))
				.addButtonData(buttonData)
				.setShape(
						RoundedRectangle(
								target,
								target.height.toFloat(),
								target.height.toFloat()
						)
				)
				.setDescription(resources.getString(R.string.tutorial_map_description))
				.build()
	}

	private fun createGameTarget(
        activity: AppCompatActivity,
        resources: Resources,
        buttonData: SimpleTarget.ButtonData
	): Target {
		val target = activity.findViewById<View>(R.id.button_game)
		return SimpleTarget.Builder(activity)
				.setPoint(target.x + target.pivotX, target.y + target.pivotY)
				.setTitle(resources.getString(R.string.tutorial_game_title))
				.addButtonData(buttonData)
				.setShape(Circle(target))
				.setDescription(resources.getString(R.string.tutorial_game_description))
				.build()
	}


	private fun createStatsTarget(
        activity: AppCompatActivity,
        resources: Resources,
        buttonData: SimpleTarget.ButtonData
	): Target {
		val target = activity.findViewById<View>(R.id.button_stats)
		return SimpleTarget.Builder(activity)
				.setPoint(target.x + target.pivotX, target.y + target.pivotY)
				.setTitle(resources.getString(R.string.tutorial_stats_title))
				.addButtonData(buttonData)
				.setShape(Circle(target))
				.setDescription(resources.getString(R.string.tutorial_stats_description))
				.build()
	}

	override fun getTargets(activity: FragmentActivity): Collection<Target> {
		val resources = activity.resources
		val buttonData = SimpleTarget.ButtonData(
				resources.getString(com.adsamcik.tracker.shared.base.R.string.generic_continue)
		) { _, spotlight ->
			spotlight.next()
		}

		return listOf(
				createSettingsTarget(activity, resources, buttonData),
				createMapTarget(activity, resources, buttonData),
				createStatsTarget(activity, resources, buttonData),
				createGameTarget(activity, resources, buttonData)
		)
	}

}

