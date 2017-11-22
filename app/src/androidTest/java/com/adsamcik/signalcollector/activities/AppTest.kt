package com.adsamcik.signalcollector.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.support.test.InstrumentationRegistry
import android.support.test.espresso.NoMatchingViewException
import android.support.test.espresso.ViewAssertion
import android.support.test.espresso.ViewInteraction
import android.support.test.espresso.matcher.ViewMatchers
import android.support.test.runner.AndroidJUnit4
import android.support.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import android.support.test.runner.lifecycle.Stage
import android.support.test.uiautomator.By
import android.support.test.uiautomator.UiDevice
import android.support.test.uiautomator.UiObject2
import android.support.test.uiautomator.Until
import android.util.Log
import android.util.MalformedJsonException
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent

import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.data.UploadStats
import com.adsamcik.signalcollector.enums.CloudStatus
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.services.MessageListenerService
import com.adsamcik.signalcollector.utility.Constants
import com.adsamcik.signalcollector.utility.Preferences
import com.google.gson.Gson

import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.StringDescription
import org.hamcrest.TypeSafeMatcher
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.HashMap

import android.support.test.InstrumentationRegistry.getInstrumentation
import android.support.test.espresso.Espresso.onView
import android.support.test.espresso.assertion.ViewAssertions.doesNotExist
import android.support.test.espresso.matcher.ViewMatchers.isDisplayed
import android.support.test.espresso.matcher.ViewMatchers.withId
import com.adsamcik.signalcollector.test.isTestMode
import org.hamcrest.Matchers.allOf
import org.hamcrest.core.IsNull.notNullValue
import org.junit.Assert.assertThat

@RunWith(AndroidJUnit4::class)
class AppTest {
    private var mDevice: UiDevice? = null
    private var context: Context? = null

    /**
     * Uses package manager to find the package name of the device launcher. Usually this package
     * is "com.android.launcher" but can be different at times. This is a generic solution which
     * works on all platforms.`
     */
    private// Create launcher Intent
            // Use PackageManager to getPref the launcher package name
    val launcherPackageName: String
        get() {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_HOME)
            val pm = InstrumentationRegistry.getContext().packageManager
            val resolveInfo = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            return resolveInfo.activityInfo.packageName
        }

    private val activityInstance: Activity
        get() {
            val currentActivity = arrayOf<Activity>(null)

            getInstrumentation().runOnMainSync {
                val resumedActivity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                val it = resumedActivity.iterator()
                currentActivity[0] = it.next()
            }

            return currentActivity[0]
        }

    @Before
    fun before() {
        // Initialize UiDevice instance
        mDevice = UiDevice.getInstance(getInstrumentation())

        val launcherPackage = launcherPackageName
        assertThat(launcherPackage, notNullValue())

        // Start from the home screen
        if (mDevice!!.currentPackageName != launcherPackage) {
            mDevice!!.pressHome()
            mDevice!!.wait(Until.hasObject(By.pkg(launcherPackage).depth(0)), LAUNCH_TIMEOUT.toLong())
        }

        // Launch the blueprint app
        context = InstrumentationRegistry.getContext()
        val intent = context!!.packageManager
                .getLaunchIntentForPackage(PACKAGE)!!
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)    // Clear out any previous instances
        context!!.startActivity(intent)

        // Wait for the app to appear
        mDevice!!.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), LAUNCH_TIMEOUT.toLong())
        context = InstrumentationRegistry.getTargetContext().applicationContext
    }

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

        Preferences.getPref(context!!).edit().putLong(Preferences.PREF_OLDEST_RECENT_UPLOAD, 20).apply()
        Assert.assertEquals(true, DataStore.saveAppendableJsonArray(context!!, testFileName, gson.toJson(us), false))
        Assert.assertEquals(true, DataStore.exists(context!!, testFileName))
        Assert.assertEquals('[' + data, DataStore.loadString(context!!, testFileName))
        Assert.assertEquals("[$data]", DataStore.loadAppendableJsonArray(context!!, testFileName))
        //DataStore.removeOldRecentUploads();
        Assert.assertEquals(true, DataStore.saveAppendableJsonArray(context!!, testFileName, us, true))
        Assert.assertEquals("[$data,$data", DataStore.loadString(context!!, testFileName))
        Assert.assertEquals("[$data,$data]", DataStore.loadAppendableJsonArray(context!!, testFileName))

        Assert.assertEquals(true, DataStore.saveAppendableJsonArray(context!!, testFileName, gson.toJson(usOld), true))
        Assert.assertEquals("[$data,$data,$dataOld", DataStore.loadString(context!!, testFileName))
        Assert.assertEquals("[$data,$data,$dataOld]", DataStore.loadAppendableJsonArray(context!!, testFileName))
        DataStore.removeOldRecentUploads(context!!)

        Assert.assertEquals("[$data,$data", DataStore.loadString(context!!, testFileName))
        Assert.assertEquals("[$data,$data]", DataStore.loadAppendableJsonArray(context!!, testFileName))

        DataStore.delete(context!!, testFileName)
        DataStore.delete(context!!, DataStore.RECENT_UPLOADS_FILE)

        val WIFI = "wifi"
        val NEW_WIFI = "newWifi"
        val CELL = "cell"
        val NEW_CELL = "newCell"
        val COLLECTIONS = "collections"
        val NEW_LOCATIONS = "newLocations"
        val SIZE = "uploadSize"

        val d = HashMap<String, String>(10)
        d.put(WIFI, Integer.toString(us.wifi))
        d.put(NEW_WIFI, Integer.toString(us.newWifi))
        d.put(CELL, Integer.toString(us.cell))
        d.put(NEW_CELL, Integer.toString(us.newCell))
        d.put(COLLECTIONS, Integer.toString(us.collections))
        d.put(NEW_LOCATIONS, Integer.toString(us.newLocations))
        d.put(SIZE, java.lang.Long.toString(us.uploadSize))

        MessageListenerService.parseAndSaveUploadReport(context!!, time, d)
        Assert.assertEquals('[' + data, DataStore.loadString(context!!, DataStore.RECENT_UPLOADS_FILE))

        MessageListenerService.parseAndSaveUploadReport(context!!, time, d)
        Assert.assertEquals("[$data,$data", DataStore.loadString(context!!, DataStore.RECENT_UPLOADS_FILE))
        DataStore.delete(context!!, DataStore.RECENT_UPLOADS_FILE)
    }

    @Test
    @Throws(Exception::class)
    fun uploadFABTest() {
        if(isTestMode)
            return

        Network.cloudStatus = CloudStatus.SYNC_AVAILABLE

        Thread.sleep(Constants.SECOND_IN_MILLISECONDS.toLong())

        mDevice!!.waitForIdle((30 * Constants.SECOND_IN_MILLISECONDS).toLong())
        val actionStats = mDevice!!.findObject(By.res(PACKAGE, "action_stats")) ?: throw Exception(mDevice!!.currentPackageName + " activity " + activityInstance.javaClass.simpleName)
        actionStats.click()
        mDevice!!.findObject(By.res(PACKAGE, "action_tracker")).click()

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

        DataStore.onUpload(context!!, 25)
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

        DataStore.onUpload(context!!, 50)
        Thread.sleep((Constants.SECOND_IN_MILLISECONDS / 2).toLong())

        DataStore.onUpload(context!!, 100)
        DataStore.incData(context!!, 500, 25)
        Network.cloudStatus = CloudStatus.SYNC_AVAILABLE
        Thread.sleep((4 * Constants.SECOND_IN_MILLISECONDS).toLong())
        fabUpload.check(matches(isDisplayed()))
        progressBar.check(doesNotExist())
    }

    companion object {
        private val TAG = "SignalsSaveLoadTest"
        private val PACKAGE = "com.adsamcik.signalcollector"
        private val LAUNCH_TIMEOUT = 5000

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
        fun matches(viewMatcher: Matcher<in View>): ViewAssertion {
            return { view: View, noViewException: NoMatchingViewException ->
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
                    ViewMatchers.assertThat<in View>(description.toString(), view, viewMatcher)
                }
            }
        }
    }
}
