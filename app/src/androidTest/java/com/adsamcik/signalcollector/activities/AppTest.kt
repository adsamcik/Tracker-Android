package com.adsamcik.signalcollector.activities

import android.content.Context
import android.support.test.InstrumentationRegistry.getInstrumentation
import android.support.test.espresso.Espresso.onView
import android.support.test.espresso.NoMatchingViewException
import android.support.test.espresso.ViewAssertion
import android.support.test.espresso.assertion.ViewAssertions.doesNotExist
import android.support.test.espresso.matcher.ViewMatchers
import android.support.test.espresso.matcher.ViewMatchers.isDisplayed
import android.support.test.espresso.matcher.ViewMatchers.withId
import android.support.test.runner.AndroidJUnit4
import android.support.test.uiautomator.By
import android.support.test.uiautomator.UiDevice
import android.util.Log
import android.util.MalformedJsonException
import android.view.View
import android.view.ViewGroup
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.device
import com.adsamcik.signalcollector.services.MessageListenerService
import com.adsamcik.signals.base.test.isTestMode
import com.google.gson.Gson
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.StringDescription
import org.hamcrest.TypeSafeMatcher
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import java.util.*

@RunWith(AndroidJUnit4::class)
class AppTest {
    private var mDevice: UiDevice = device
    private var context: Context = getInstrumentation().targetContext

    @Test
    @Throws(MalformedJsonException::class, InterruptedException::class)
    fun notificationSavingTest() {
        val testFileName = DataStore.RECENT_UPLOADS_FILE
        val gson = Gson()

        val time = System.currentTimeMillis()
        val us = UploadStats(time, 2500, 10, 130, 1, 130, 2, 0, 10654465, 0)
        val usOld = UploadStats(20, 2500, 10, 130, 1, 130, 2, 0, 10654465, 0)
        val data = gson.toJson(us)
        val dataOld = gson.toJson(usOld)

        Preferences.getPref(context).edit().putLong(Preferences.PREF_OLDEST_RECENT_UPLOAD, 20).apply()
        Assert.assertEquals(true, DataStore.saveAppendableJsonArray(context, testFileName, gson.toJson(us), false))
        Assert.assertEquals(true, DataStore.exists(context, testFileName))
        Assert.assertEquals('[' + data, DataStore.loadString(context, testFileName))
        Assert.assertEquals("[$data]", DataStore.loadAppendableJsonArray(context, testFileName))
        //DataStore.removeOldRecentUploads();
        Assert.assertEquals(true, DataStore.saveAppendableJsonArray(context, testFileName, us, true))
        Assert.assertEquals("[$data,$data", DataStore.loadString(context, testFileName))
        Assert.assertEquals("[$data,$data]", DataStore.loadAppendableJsonArray(context, testFileName))

        Assert.assertEquals(true, DataStore.saveAppendableJsonArray(context, testFileName, gson.toJson(usOld), true))
        Assert.assertEquals("[$data,$data,$dataOld", DataStore.loadString(context, testFileName))
        Assert.assertEquals("[$data,$data,$dataOld]", DataStore.loadAppendableJsonArray(context, testFileName))
        DataStore.removeOldRecentUploads(context)

        Assert.assertEquals("[$data,$data", DataStore.loadString(context, testFileName))
        Assert.assertEquals("[$data,$data]", DataStore.loadAppendableJsonArray(context, testFileName))

        DataStore.delete(context, testFileName)
        DataStore.delete(context, DataStore.RECENT_UPLOADS_FILE)

        val WIFI = "wifi"
        val NEW_WIFI = "newWifi"
        val CELL = "cell"
        val NEW_CELL = "newCell"
        val COLLECTIONS = "collections"
        val NEW_LOCATIONS = "newLocations"
        val SIZE = "uploadSize"

        val d = HashMap<String, String>(10)
        d[WIFI] = Integer.toString(us.wifi)
        d[NEW_WIFI] = Integer.toString(us.newWifi)
        d[CELL] = Integer.toString(us.cell)
        d[NEW_CELL] = Integer.toString(us.newCell)
        d[COLLECTIONS] = Integer.toString(us.collections)
        d[NEW_LOCATIONS] = Integer.toString(us.newLocations)
        d[SIZE] = java.lang.Long.toString(us.uploadSize)

        MessageListenerService.parseAndSaveUploadReport(context, time, d)
        Assert.assertEquals('[' + data, DataStore.loadString(context, DataStore.RECENT_UPLOADS_FILE))

        MessageListenerService.parseAndSaveUploadReport(context, time, d)
        Assert.assertEquals("[$data,$data", DataStore.loadString(context, DataStore.RECENT_UPLOADS_FILE))
        DataStore.delete(context, DataStore.RECENT_UPLOADS_FILE)
    }

    @Test
    @Throws(Exception::class)
    fun uploadFABTest() {
        if (isTestMode)
            return

        Network.cloudStatus = com.adsamcik.signals.network.network.CloudStatus.SYNC_AVAILABLE

        Thread.sleep(Constants.SECOND_IN_MILLISECONDS.toLong())

        mDevice.waitForIdle((30 * Constants.SECOND_IN_MILLISECONDS).toLong())
        val actionStats = mDevice.findObject(By.res(PACKAGE, "action_stats"))
        actionStats.click()
        mDevice.findObject(By.res(PACKAGE, "action_tracker")).click()

        Thread.sleep((Constants.SECOND_IN_MILLISECONDS / 2).toLong())

        val fabUpload = onView(
                allOf(withId(R.id.fabTwo),
                        childAtPosition(
                                childAtPosition(
                                        childAtPosition(
                                                withId(R.id.fabCoordinator),
                                                0),
                                        0),
                                0),
                        isDisplayed()))

        fabUpload.check(matches(isDisplayed()))

        DataStore.onUpload(context, 25)
        Thread.sleep(500)

        val progressBar = onView(
                allOf(withId(R.id.progressBar),
                        childAtPosition(
                                childAtPosition(
                                        childAtPosition(
                                                withId(R.id.fabCoordinator),
                                                0),
                                        0),
                                1),
                        isDisplayed()))
        progressBar.check(matches(isDisplayed()))

        DataStore.onUpload(context, 50)
        Thread.sleep((Constants.SECOND_IN_MILLISECONDS / 2).toLong())

        DataStore.onUpload(context, 100)
        DataStore.incData(context, 500, 25)
        Network.cloudStatus = com.adsamcik.signals.network.network.CloudStatus.SYNC_AVAILABLE
        Thread.sleep((4 * Constants.SECOND_IN_MILLISECONDS).toLong())
        fabUpload.check(matches(isDisplayed()))
        progressBar.check(doesNotExist())
    }

    companion object {
        private const val TAG = "SignalsSaveLoadTest"
        private const val PACKAGE = "com.adsamcik.signalcollector"
        private const val LAUNCH_TIMEOUT = 5000

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

        /**
         * Returns a generic [ViewAssertion] that asserts that a view exists in the view hierarchy
         * and is matched by the given view matcher.
         */
        fun matches(viewMatcher: Matcher<View>): ViewAssertion {
            return ViewAssertion { view: View?, noViewException: NoMatchingViewException? ->
                val description = StringDescription()
                description.appendText("'")
                viewMatcher.describeTo(description)
                if (noViewException != null) {
                    description.appendText(String.format(
                            "' check could not be performed because view '%s' was not found.\n",
                            noViewException.viewMatcherDescription))
                    Log.e(TAG, description.toString())
                    throw noViewException
                } else {
                    description.appendText("' doesn't match the selected view.")
                    ViewMatchers.assertThat<View>(description.toString(), view, viewMatcher)
                }
            }
        }
    }
}
