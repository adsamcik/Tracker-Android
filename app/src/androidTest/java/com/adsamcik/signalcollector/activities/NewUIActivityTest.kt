package com.adsamcik.signalcollector.activities


import android.support.test.espresso.ViewInteraction
import android.support.test.rule.ActivityTestRule
import android.support.test.rule.GrantPermissionRule
import android.support.test.runner.AndroidJUnit4
import android.test.suitebuilder.annotation.LargeTest
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent

import com.adsamcik.signalcollector.R

import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

import android.support.test.espresso.Espresso.onView
import android.support.test.espresso.action.ViewActions.click
import android.support.test.espresso.assertion.ViewAssertions.matches
import android.support.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import android.support.test.espresso.matcher.ViewMatchers.isDisplayed
import android.support.test.espresso.matcher.ViewMatchers.withClassName
import android.support.test.espresso.matcher.ViewMatchers.withContentDescription
import android.support.test.espresso.matcher.ViewMatchers.withId
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.`is`

@LargeTest
@RunWith(AndroidJUnit4::class)
class NewUIActivityTest {

    @Rule
    val mActivityTestRule = ActivityTestRule(NewUIActivity::class.java)

    @Rule
    val mPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.ACCESS_FINE_LOCATION)

    @Test
    fun newUIActivityTest() {
        // Added a sleep statement to match the app's execution delay.
        // The recommended way to handle such scenarios is to use Espresso idling resources:
        // https://google.github.io/android-testing-support-library/docs/espresso/idling-resource/index.html
        try {
            Thread.sleep(3598564)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        val spotlightView = onView(
                allOf(withClassName(`is`("com.takusemba.spotlight.SpotlightView")), isDisplayed()))
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

        // Added a sleep statement to match the app's execution delay.
        // The recommended way to handle such scenarios is to use Espresso idling resources:
        // https://google.github.io/android-testing-support-library/docs/espresso/idling-resource/index.html
        try {
            Thread.sleep(60000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        val imageButton = onView(
                allOf(withId(R.id.statsButton),
                        childAtPosition(
                                allOf(withId(R.id.root),
                                        childAtPosition(
                                                withId(android.R.id.content),
                                                0)),
                                1),
                        isDisplayed()))
        imageButton.check(matches(isDisplayed()))

        val imageButton2 = onView(
                allOf(withId(R.id.mapDraggable),
                        childAtPosition(
                                allOf(withId(R.id.root),
                                        childAtPosition(
                                                withId(android.R.id.content),
                                                0)),
                                3),
                        isDisplayed()))
        imageButton2.check(matches(isDisplayed()))

        val imageButton3 = onView(
                allOf(withId(R.id.activityButton),
                        childAtPosition(
                                allOf(withId(R.id.root),
                                        childAtPosition(
                                                withId(android.R.id.content),
                                                0)),
                                2),
                        isDisplayed()))
        imageButton3.check(matches(isDisplayed()))

        val linearLayout = onView(
                allOf(withId(R.id.top_panel),
                        childAtPosition(
                                childAtPosition(
                                        withId(R.id.root),
                                        0),
                                1),
                        isDisplayed()))
        linearLayout.check(matches(isDisplayed()))

        // Added a sleep statement to match the app's execution delay.
        // The recommended way to handle such scenarios is to use Espresso idling resources:
        // https://google.github.io/android-testing-support-library/docs/espresso/idling-resource/index.html
        try {
            Thread.sleep(5000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        val imageButton4 = onView(
                allOf(withId(R.id.button_settings),
                        childAtPosition(
                                allOf(withId(R.id.top_panel),
                                        childAtPosition(
                                                withClassName(`is`("android.support.constraint.ConstraintLayout")),
                                                0)),
                                3),
                        isDisplayed()))
        imageButton4.perform(click())

        // Added a sleep statement to match the app's execution delay.
        // The recommended way to handle such scenarios is to use Espresso idling resources:
        // https://google.github.io/android-testing-support-library/docs/espresso/idling-resource/index.html
        try {
            Thread.sleep(3527494)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        val recyclerView = onView(
                allOf(withId(R.id.list),
                        childAtPosition(
                                withId(android.R.id.list_container),
                                0)))
        recyclerView.perform(actionOnItemAtPosition<ViewHolder>(0, click()))

        // Added a sleep statement to match the app's execution delay.
        // The recommended way to handle such scenarios is to use Espresso idling resources:
        // https://google.github.io/android-testing-support-library/docs/espresso/idling-resource/index.html
        try {
            Thread.sleep(3596285)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        val appCompatImageButton = onView(
                allOf(withId(R.id.back_button), withContentDescription("back"),
                        childAtPosition(
                                childAtPosition(
                                        withId(R.id.content_detail_root),
                                        0),
                                0),
                        isDisplayed()))
        appCompatImageButton.perform(click())

        // Added a sleep statement to match the app's execution delay.
        // The recommended way to handle such scenarios is to use Espresso idling resources:
        // https://google.github.io/android-testing-support-library/docs/espresso/idling-resource/index.html
        try {
            Thread.sleep(3597442)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        val recyclerView2 = onView(
                allOf(withId(R.id.list),
                        childAtPosition(
                                withId(android.R.id.list_container),
                                0)))
        recyclerView2.perform(actionOnItemAtPosition<ViewHolder>(2, click()))

        // Added a sleep statement to match the app's execution delay.
        // The recommended way to handle such scenarios is to use Espresso idling resources:
        // https://google.github.io/android-testing-support-library/docs/espresso/idling-resource/index.html
        try {
            Thread.sleep(3596933)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        val appCompatImageButton2 = onView(
                allOf(withId(R.id.back_button), withContentDescription("back"),
                        childAtPosition(
                                childAtPosition(
                                        withId(R.id.content_detail_root),
                                        0),
                                0),
                        isDisplayed()))
        appCompatImageButton2.perform(click())

        // Added a sleep statement to match the app's execution delay.
        // The recommended way to handle such scenarios is to use Espresso idling resources:
        // https://google.github.io/android-testing-support-library/docs/espresso/idling-resource/index.html
        try {
            Thread.sleep(3596423)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        val imageButton5 = onView(
                allOf(withId(R.id.button_tracking),
                        childAtPosition(
                                allOf(withId(R.id.top_panel),
                                        childAtPosition(
                                                withClassName(`is`("android.support.constraint.ConstraintLayout")),
                                                0)),
                                2),
                        isDisplayed()))
        imageButton5.perform(click())

        val imageButton6 = onView(
                allOf(withId(R.id.button_tracking),
                        childAtPosition(
                                allOf(withId(R.id.top_panel),
                                        childAtPosition(
                                                withClassName(`is`("android.support.constraint.ConstraintLayout")),
                                                0)),
                                2),
                        isDisplayed()))
        imageButton6.perform(click())

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
