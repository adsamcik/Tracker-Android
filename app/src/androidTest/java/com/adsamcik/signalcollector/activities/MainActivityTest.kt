package com.adsamcik.signalcollector.activities


import android.view.View
import android.view.ViewGroup
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.rule.ActivityTestRule
import androidx.test.rule.GrantPermissionRule
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.exists
import com.adsamcik.signalcollector.services.TrackerService
import com.adsamcik.signalcollector.test.isTestMode
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.allOf
import org.hamcrest.TypeSafeMatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith


@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class MainActivityTest {

	@get:Rule
	val mPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.ACCESS_FINE_LOCATION)!!

	@get:Rule
	val mActivityTestRule = ActivityTestRule(MainActivity::class.java)

	@Test
	fun newUIActivityTest() {
		if (isTestMode)
			return

		val spotlightView = onView(
				allOf(withClassName(`is`("com.takusemba.spotlight.SpotlightView")), isDisplayed()))

		if (spotlightView.exists()) {
			spotlightView.perform(click())

			val spotlightView2 = onView(
					allOf(withClassName(`is`("com.takusemba.spotlight.SpotlightView")), isDisplayed()))
			spotlightView2.perform(click())

			val spotlightView3 = onView(
					allOf(withClassName(`is`("com.takusemba.spotlight.SpotlightView")), isDisplayed()))
			spotlightView3.perform(click())

			val spotlightView4 = onView(
					allOf(withClassName(`is`("com.takusemba.spotlight.SpotlightView")), isDisplayed()))
			spotlightView4.perform(click())

			val spotlightView5 = onView(
					allOf(withClassName(`is`("com.takusemba.spotlight.SpotlightView")), isDisplayed()))
			spotlightView5.perform(click())

			sleep()
		}

		val statsButton = onView(withId(R.id.button_stats)).check(matches(isDisplayed()))
		val mapDraggable = onView(withId(R.id.button_map)).check(matches(isDisplayed()))
		val activityButton = onView(withId(R.id.button_activity)).check(matches(isDisplayed()))
		val topPanel = onView(withId(R.id.top_panel)).check(matches(isDisplayed()))

		mapDraggable.perform(click())

		sleep()

		if (spotlightView.exists()) {
			onView(withText(mActivityTestRule.activity.getString(R.string.skip_tips))).check(matches(isDisplayed())).perform(click())
			sleep()
		}

		mapDraggable.perform(click())

		sleep()

		val imageButton4 = onView(withId(R.id.button_settings)).check(matches(isDisplayed()))
		imageButton4.perform(click())

		sleep()

		onView(allOf(withId(R.id.back_button), withContentDescription("back"), isDisplayed())).perform(click())

		sleep()

		val imageButton5 = onView(withId(R.id.button_tracking)).check(matches(isDisplayed())).perform(click())

		sleep()

		assert(TrackerService.isServiceRunning.value)
		assert(!TrackerService.isBackgroundActivated)

		imageButton5.perform(click())

	}

	private fun sleep(millis: Long = 200) {
		try {
			Thread.sleep(millis)
		} catch (e: InterruptedException) {
			e.printStackTrace()
		}
	}

	private fun childAtPosition(
			parentMatcher: Matcher<View>, position: Int): Matcher<View> {

		return object : TypeSafeMatcher<View>() {
			override fun describeTo(description: Description) {
				description.appendText("Child at position $position in parent ")
				parentMatcher.describeTo(description)
			}

			public override fun matchesSafely(view: View): Boolean {
				val parent = view.parent
				return (parent is ViewGroup && parentMatcher.matches(parent)
						&& view == parent.getChildAt(position))
			}
		}
	}
}
