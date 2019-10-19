package com.adsamcik.tracker.app

import android.app.Activity
import android.content.res.Resources
import android.graphics.PointF
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.adsamcik.tracker.R
import com.adsamcik.tracker.common.introduction.Introduction
import com.takusemba.spotlight.SimpleTarget
import com.takusemba.spotlight.Target
import com.takusemba.spotlight.shapes.Circle
import com.takusemba.spotlight.shapes.RoundedRectangle

class HomeIntroduction : Introduction() {
	override val key: String = "home_tips"

	private fun createWelcomeTarget(
			activity: Activity,
			resources: Resources,
			buttonData: SimpleTarget.ButtonData
	): Target = SimpleTarget.Builder(activity)
			.setTitle(resources.getString(R.string.tutorial_welcome_title))
			.addButtonData(SimpleTarget.ButtonData(
					resources.getString(
							com.adsamcik.tracker.common.R.string.skip_introduction
					)
			) { _, spotlight ->
				spotlight.finishSpotlight()
			})
			.addButtonData(buttonData)
			.setShape(Circle(PointF(0f, 0f), 0f))
			.setDescription(resources.getString(R.string.tutorial_welcome_description))
			.build()

	private fun createSettingsTarget(
			activity: Activity,
			resources: Resources,
			buttonData: SimpleTarget.ButtonData
	): Target {
		val target = activity.findViewById<View>(R.id.button_settings)
		return SimpleTarget.Builder(activity)
				.setPoint(target.x + target.pivotX, target.y + target.pivotY)
				.setTitle(resources.getString(R.string.tutorial_settings_title))
				.addButtonData(buttonData)
				.setShape(Circle(target))
				.setDescription(resources.getString(R.string.tutorial_settings_description))
				.build()
	}


	private fun createMapTarget(
			activity: Activity,
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
			activity: Activity,
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
			activity: Activity,
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
				resources.getString(com.adsamcik.tracker.common.R.string.next_part)
		) { _, spotlight ->
			spotlight.next()
		}

		return listOf(
				createWelcomeTarget(activity, resources, buttonData),
				createSettingsTarget(activity, resources, buttonData),
				createMapTarget(activity, resources, buttonData),
				createStatsTarget(activity, resources, buttonData),
				createGameTarget(activity, resources, buttonData)
		)
	}

}

