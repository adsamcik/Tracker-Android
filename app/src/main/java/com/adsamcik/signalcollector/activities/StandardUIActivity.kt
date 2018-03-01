package com.adsamcik.signalcollector.activities

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.support.annotation.StringRes
import android.support.design.widget.BottomNavigationView
import android.support.design.widget.FloatingActionButton
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentActivity
import android.support.v4.content.ContextCompat
import android.util.Log
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.enums.CloudStatus
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.fragments.*
import com.adsamcik.signalcollector.interfaces.ITabFragment
import com.adsamcik.signalcollector.jobs.UploadJobService
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.services.ActivityService
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Constants
import com.adsamcik.signalcollector.utility.Preferences
import com.adsamcik.signalcollector.utility.SnackMaker
import com.crashlytics.android.Crashlytics

class StandardUIActivity : FragmentActivity() {

    private var fabOne: FloatingActionButton? = null
    private var fabTwo: FloatingActionButton? = null

    private var currentFragment: ITabFragment? = null

    private val BUNDLE_FRAGMENT = "fragment"

    override fun onCreate(savedInstanceState: Bundle?) {
        Preferences.setTheme(this)
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        val snackMaker = SnackMaker(this)
        Assist.initialize(this)

        if (Network.cloudStatus == CloudStatus.UNKNOWN) {
            val scheduleSource = UploadJobService.getUploadScheduled(this)
            when (scheduleSource) {
                UploadJobService.UploadScheduleSource.NONE -> Network.cloudStatus = if (DataStore.sizeOfData(this) >= Constants.MIN_USER_UPLOAD_FILE_SIZE) CloudStatus.SYNC_AVAILABLE else CloudStatus.NO_SYNC_REQUIRED
                UploadJobService.UploadScheduleSource.BACKGROUND, UploadJobService.UploadScheduleSource.USER -> Network.cloudStatus = CloudStatus.SYNC_SCHEDULED
            }
        }

        Signin.signIn(this, null, true)

        if (Assist.checkPlayServices(this))
            ActivityService.requestAutoTracking(this, StandardUIActivity::class.java)
        else
            snackMaker.showSnackbar(R.string.error_play_services_not_available)

        val primary = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_primary))
        val secondary = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_accent))

        fabOne = findViewById(R.id.fabOne)
        fabOne!!.backgroundTintList = secondary
        fabOne!!.imageTintList = primary

        fabTwo = findViewById(R.id.fabTwo)
        fabTwo!!.backgroundTintList = primary
        fabTwo!!.imageTintList = secondary

        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)

        bottomNavigationView.setOnNavigationItemSelectedListener { item -> changeFragment(item.itemId) }

        val currentFragment = if (savedInstanceState != null && savedInstanceState.containsKey(BUNDLE_FRAGMENT)) savedInstanceState.getInt(BUNDLE_FRAGMENT) else R.id.action_tracker
        bottomNavigationView.selectedItemId = currentFragment
    }

    private fun changeFragment(index: Int): Boolean {
        when (index) {
            R.id.action_tracker -> handleBottomNav(FragmentTracker::class.java, R.string.menu_tracker)
            R.id.action_map -> handleBottomNav(FragmentMap::class.java, R.string.menu_map)
            R.id.action_stats -> handleBottomNav(FragmentStats::class.java, R.string.menu_stats)
            R.id.action_settings -> handleBottomNav(FragmentSettings::class.java, R.string.menu_settings)
            R.id.action_activities -> handleBottomNav(FragmentActivities::class.java, R.string.menu_activities)
            else -> {
                Log.e(TAG, "Unknown fragment item id $index")
                return false
            }
        }
        return true
    }

    override fun onSaveInstanceState(outState: Bundle?) {
        super.onSaveInstanceState(outState)

        when (currentFragment!!.javaClass.simpleName) {
            "FragmentTracker" -> outState!!.putInt(BUNDLE_FRAGMENT, R.id.action_tracker)
            "FragmentMap" -> outState!!.putInt(BUNDLE_FRAGMENT, R.id.action_map)
            "FragmentStats" -> outState!!.putInt(BUNDLE_FRAGMENT, R.id.action_stats)
            "FragmentSettings" -> outState!!.putInt(BUNDLE_FRAGMENT, R.id.action_settings)
            "FragmentActivities" -> outState!!.putInt(BUNDLE_FRAGMENT, R.id.action_activities)
        }
    }

    private fun <T : ITabFragment> handleBottomNav(tClass: Class<T>, @StringRes resId: Int) {
        if (currentFragment != null && currentFragment!!.javaClass == tClass)
            currentFragment!!.onHomeAction()
        else {
            fabOne!!.hide()
            fabTwo!!.hide()

            val fragmentManager = supportFragmentManager
            val fragmentTransaction = fragmentManager.beginTransaction()
            //fragmentTransaction.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out);

            if (currentFragment != null) {
                currentFragment!!.onLeave(this)
                fragmentTransaction.remove(currentFragment as Fragment?)
            }

            try {
                currentFragment = tClass.newInstance()
            } catch (e: InstantiationException) {
                Crashlytics.logException(e)
                return
            } catch (e: IllegalAccessException) {
                Crashlytics.logException(e)
                return
            }

            val str = getString(resId)
            fragmentTransaction.replace(R.id.container, currentFragment as Fragment?, str)

            val state = currentFragment!!.onEnter(this, fabOne!!, fabTwo!!)
            fragmentTransaction.commit()

            if (state.hasFailed())
                SnackMaker(this).showSnackbar(state.value!!)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == 0)
            return
        val success = grantResults.none { it != PackageManager.PERMISSION_GRANTED }
        if (currentFragment != null)
            currentFragment!!.onPermissionResponse(requestCode, success)
    }


    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == Signin.RC_SIGN_IN) {
            Signin.onSignResult(this, resultCode, data)
        }
    }

    companion object {
        const val TAG = "SignalsMainActivity"
    }
}
