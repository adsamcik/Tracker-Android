package com.adsamcik.signalcollector.activities


import android.support.test.espresso.Espresso.onView
import android.support.test.espresso.action.ViewActions.click
import android.support.test.espresso.assertion.ViewAssertions.matches
import android.support.test.espresso.matcher.ViewMatchers.isDisplayed
import android.support.test.espresso.matcher.ViewMatchers.withId
import android.support.test.filters.LargeTest
import android.support.test.rule.ActivityTestRule
import android.support.test.runner.AndroidJUnit4
import android.view.View
import android.view.ViewGroup
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.handlePermissions
import com.adsamcik.signalcollector.sleep
import com.adsamcik.signalcollector.test.isTestMode
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.TypeSafeMatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class TrackerFragmentTest {

    @Rule @JvmField
    val mActivityTestRule = ActivityTestRule(MainActivity::class.java)

    @Test
    fun trackerFragmentTest() {
        if(isTestMode)
            return
        sleep(3000)

        val floatingActionButton = onView(
                allOf(withId(R.id.fabOne),
                        childAtPosition(
                                childAtPosition(
                                        withId(R.id.fabCoordinator),
                                        0),
                                1),
                        isDisplayed()))
        floatingActionButton.perform(click())

        sleep(400)

        handlePermissions(false)

        sleep(400)

        val floatingActionButton2 = onView(
                allOf(withId(R.id.fabOne),
                        childAtPosition(
                                childAtPosition(
                                        withId(R.id.fabCoordinator),
                                        0),
                                1),
                        isDisplayed()))
        floatingActionButton2.perform(click())

        sleep(400)

        handlePermissions(true)

        sleep(400)

        val floatingActionButton3 = onView(
                allOf(withId(R.id.fabOne),
                        childAtPosition(
                                childAtPosition(
                                        withId(R.id.fabCoordinator),
                                        0),
                                1),
                        isDisplayed()))
        floatingActionButton3.perform(click())

        sleep(400)


        val floatingActionButton4 = onView(
                allOf(withId(R.id.fabOne),
                        childAtPosition(
                                childAtPosition(
                                        withId(R.id.fabCoordinator),
                                        0),
                                1),
                        isDisplayed()))
        floatingActionButton4.perform(click())

        sleep(400)

        val imageButton = onView(
                allOf(withId(R.id.fabOne),
                        childAtPosition(
                                childAtPosition(
                                        withId(R.id.fabCoordinator),
                                        0),
                                1),
                        isDisplayed()))
        imageButton.check(matches(isDisplayed()))

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
