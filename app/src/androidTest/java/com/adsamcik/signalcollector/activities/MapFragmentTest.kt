package com.adsamcik.signalcollector.activities


import android.support.test.espresso.Espresso.onView
import android.support.test.espresso.action.ViewActions.*
import android.support.test.espresso.assertion.ViewAssertions.matches
import android.support.test.espresso.matcher.ViewMatchers.*
import android.support.test.filters.LargeTest
import android.support.test.rule.ActivityTestRule
import android.support.test.runner.AndroidJUnit4
import android.view.View
import android.view.ViewGroup
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.handlePermissions
import com.adsamcik.signalcollector.sleep
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.TypeSafeMatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class MapFragmentTest {

    @Rule @JvmField
    val mActivityTestRule = ActivityTestRule(MainActivity::class.java)

    @Test
    fun mapFragmentTest() {
        sleep(500)

        val bottomNavigationItemView = onView(
                allOf(withId(R.id.action_map),
                        childAtPosition(
                                childAtPosition(
                                        withId(R.id.bottom_navigation),
                                        0),
                                1),
                        isDisplayed()))
        bottomNavigationItemView.perform(click())

        sleep(500)

        handlePermissions(false)

        sleep(300)

        val textView = onView(
                allOf(withId(R.id.activity_error_text), withText("App does not have required permission"),
                        childAtPosition(
                                childAtPosition(
                                        withId(R.id.container),
                                        0),
                                0),
                        isDisplayed()))
        textView.check(matches(isDisplayed()))

        sleep(100)

        val bottomNavigationItemView2 = onView(
                allOf(withId(R.id.action_tracker),
                        childAtPosition(
                                childAtPosition(
                                        withId(R.id.bottom_navigation),
                                        0),
                                0),
                        isDisplayed()))
        bottomNavigationItemView2.perform(click())

        sleep(100)

        val bottomNavigationItemView3 = onView(
                allOf(withId(R.id.action_map),
                        childAtPosition(
                                childAtPosition(
                                        withId(R.id.bottom_navigation),
                                        0),
                                1),
                        isDisplayed()))
        bottomNavigationItemView3.perform(click())

        sleep(200)

        handlePermissions(true)

        sleep(1000)

        val editText = onView(
                allOf(withId(R.id.map_search),
                        childAtPosition(
                                allOf(withId(R.id.container_map),
                                        childAtPosition(
                                                withId(R.id.container),
                                                0)),
                                0),
                        isDisplayed()))
        editText.perform(replaceText("Czech Republic"), closeSoftKeyboard())

        val editText2 = onView(
                allOf(withId(R.id.map_search), withText("Czech Republic"),
                        childAtPosition(
                                allOf(withId(R.id.container_map),
                                        childAtPosition(
                                                withId(R.id.container),
                                                0)),
                                0),
                        isDisplayed()))
        editText2.perform(pressImeActionButton())

        sleep(1000)

        val floatingActionButton = onView(
                allOf(withId(R.id.fabOne),
                        childAtPosition(
                                childAtPosition(
                                        withId(R.id.fabCoordinator),
                                        0),
                                1),
                        isDisplayed()))
        floatingActionButton.perform(click())

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
