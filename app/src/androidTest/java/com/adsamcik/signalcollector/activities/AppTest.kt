package com.adsamcik.signalcollector.activities

import android.content.Context
import android.support.test.InstrumentationRegistry.getInstrumentation
import android.support.test.espresso.NoMatchingViewException
import android.support.test.espresso.ViewAssertion
import android.support.test.espresso.matcher.ViewMatchers
import android.support.test.runner.AndroidJUnit4
import android.support.test.uiautomator.UiDevice
import android.util.Log
import android.util.MalformedJsonException
import android.view.View
import android.view.ViewGroup
import com.adsamcik.signalcollector.data.UploadStats
import com.adsamcik.signalcollector.device
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.services.MessageListenerService
import com.adsamcik.signalcollector.utility.Preferences
import com.google.gson.Gson
import org.hamcrest.Description
import org.hamcrest.Matcher
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
        val us = UploadStats(time, 2500, 10, 130, 1, 130, 2, 10654465)
        val usOld = UploadStats(20, 2500, 10, 130, 1, 130, 2, 10654465)
        val data = gson.toJson(us)
        val dataOld = gson.toJson(usOld)

        Preferences.getPref(context).edit().putLong(Preferences.PREF_OLDEST_RECENT_UPLOAD, 20).apply()
        Assert.assertEquals(true, DataStore.saveAppendableJsonArray(context, testFileName, gson.toJson(us), false))
        Assert.assertEquals(true, DataStore.exists(context, testFileName))
        Assert.assertEquals("[$data", DataStore.loadString(context, testFileName))
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

        val d = HashMap<String, String>(10)
        d[MessageListenerService.WIFI] = Integer.toString(us.wifi)
        d[MessageListenerService.NEW_WIFI] = Integer.toString(us.newWifi)
        d[MessageListenerService.CELL] = Integer.toString(us.cell)
        d[MessageListenerService.NEW_CELL] = Integer.toString(us.newCell)
        d[MessageListenerService.COLLECTIONS] = Integer.toString(us.collections)
        d[MessageListenerService.NEW_LOCATIONS] = Integer.toString(us.newLocations)
        d[MessageListenerService.UPLOAD_SIZE] = java.lang.Long.toString(us.uploadSize)

        MessageListenerService.parseAndSaveUploadReport(context, time, d)
        Assert.assertEquals("[$data", DataStore.loadString(context, DataStore.RECENT_UPLOADS_FILE))

        MessageListenerService.parseAndSaveUploadReport(context, time, d)
        Assert.assertEquals("[$data,$data", DataStore.loadString(context, DataStore.RECENT_UPLOADS_FILE))
        DataStore.delete(context, DataStore.RECENT_UPLOADS_FILE)
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
