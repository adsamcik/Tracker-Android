package com.adsamcik.signalcollector.activities

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.view.View
import android.view.WindowManager
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.fragments.FragmentIntro
import com.adsamcik.signals.signin.signin.Signin
import com.adsamcik.signalcollector.utility.Preferences
import com.adsamcik.signalcollector.utility.SnackMaker
import com.crashlytics.android.Crashlytics
import com.github.paolorotolo.appintro.AppIntro2
import java.util.*

class IntroActivity : AppIntro2() {
    private val TAG = "SignalsIntro"
    private val LOCATION_PERMISSION_REQUEST_CODE = 201
    private var autoUploadDialog: AlertDialog.Builder? = null
    private var openedTrackingAlert = false
    private var openedSigninAlert = false
    private var openedThemeAlert = false

    private var currentFragment: Fragment? = null

    private var requestedTracking = 0

    private val progress: Int
        get() = if (isRtl) slidesNumber - pager.currentItem - 1 else pager.currentItem

    override fun onCreate(savedInstanceState: Bundle?) {
        Preferences.setTheme(this)
        super.onCreate(savedInstanceState)
        val window = window
        val r = resources
        /*if (Build.VERSION.SDK_INT > 22 && (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_DENIED || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED)) {
			addSlide(FragmentIntro.newInstance(r.getString(R.string.intro_permissions_title), r.getString(R.string.intro_permissions), R.drawable.ic_permissions, Color.parseColor("#b35959"), window));
			askForPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.READ_PHONE_STATE}, 1);
		}*/
        setFadeAnimation()
        setColorTransitionsEnabled(true)

        val themeCallback = {
            if (!openedThemeAlert && progress == 0) {
                openedThemeAlert = true
                AlertDialog.Builder(this)
                        .setTitle(R.string.intro_theme_select_title)
                        .setPositiveButton(R.string.intro_theme_light) { _, _ ->
                            Preferences.setTheme(this, R.style.AppThemeLight)
                            setTheme(R.style.AppThemeLight)
                            nextSlide(0)
                        }
                        .setNegativeButton(R.string.intro_theme_dark) { _, _ ->
                            Preferences.setTheme(this, R.style.AppThemeDark)
                            setTheme(R.style.AppThemeDark)
                            nextSlide(0)
                        }
                        .setCancelable(false)
                        .show()
            }
        }

        val automationSlideCallback = {
            if (!openedTrackingAlert && progress == 1) {
                openedTrackingAlert = true

                val options = resources.getStringArray(R.array.background_tracking_options)
                AlertDialog.Builder(this)
                        .setTitle(R.string.intro_enable_auto_tracking_title)
                        .setMessage(if (Build.VERSION.SDK_INT >= 23) R.string.intro_enable_auto_tracking_description_23 else R.string.intro_enable_auto_tracking_description)
                        .setPositiveButton(options[2]) { _, _ -> trackingDialogResponse(2) }
                        .setNegativeButton(options[1]) { _, _ -> trackingDialogResponse(1) }
                        .setNeutralButton(options[0]) { _, _ -> trackingDialogResponse(0) }
                        .setCancelable(false)
                        .show()
            }
        }


        val uploadSetCallback = { value : Int ->
            Preferences.getPref(this).edit().putInt(Preferences.PREF_AUTO_UPLOAD, value).apply()
            nextSlide(1)
        }

        val uploadOptions = resources.getStringArray(R.array.automatic_upload_options)
        autoUploadDialog = AlertDialog.Builder(this)
                .setTitle(R.string.intro_enable_auto_upload_title)
                .setMessage(R.string.intro_enable_auto_upload_description)
                .setCancelable(false)
                .setPositiveButton(uploadOptions[2]) { _, _ -> uploadSetCallback.invoke(2) }
                .setNeutralButton(uploadOptions[1]) { _, _ -> uploadSetCallback.invoke(1) }
                .setNegativeButton(uploadOptions[0]) { _, _ -> uploadSetCallback.invoke(0) }
        //askForPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 2);


        val googleSigninSlideCallback = {
            if (!openedSigninAlert && progress == 2) {
                openedSigninAlert = true

                val v = layoutInflater.inflate(R.layout.intro_dialog_signin, null)
                val dialog = AlertDialog.Builder(this)
                        .setTitle(R.string.signin)
                        .setNegativeButton(R.string.cancel) { _, _ -> Preferences.getPref(this).edit().putInt(Preferences.PREF_AUTO_TRACKING, 0).apply() }
                        .setCancelable(true)
                        .create()

                v.findViewById<View>(R.id.sign_in_button).setOnClickListener { _ ->
                    val currentFragment = currentFragment!!
                    dialog.getButton(DialogInterface.BUTTON_NEGATIVE).isEnabled = false
                    dialog.setMessage(getString(R.string.signin_connecting))
                    val activity = currentFragment.activity
                    if (activity == null) {
                        Crashlytics.logException(Throwable("Activity was null during Intro"))
                        SnackMaker(currentFragment.view!!).showSnackbar(R.string.error_failed_signin)
                        dialog.dismiss()
                    } else {
                        Signin.signIn(activity, { user ->
                            if (user == null)
                                SnackMaker(currentFragment.view!!).showSnackbar(R.string.error_failed_signin)
                            dialog.dismiss()
                        }, false)
                    }

                }

                dialog.setView(v)
                dialog.show()
            }
        }

        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.parseColor("#11A63D")

        isProgressButtonEnabled = true
        setNavBarColor("#4c6699")
        skipButtonEnabled = false

        addSlide(FragmentIntro.newInstance(r.getString(R.string.intro_welcome_title), r.getString(R.string.intro_welcome_description), R.drawable.ic_intro_theme, Color.parseColor("#8B8B8B"), window, themeCallback))
        addSlide(FragmentIntro.newInstance(r.getString(R.string.intro_auto_track_up_title), r.getString(R.string.intro_auto_track_up), R.drawable.ic_intro_auto_tracking_upload, Color.parseColor("#4c6699"), window, automationSlideCallback))
        addSlide(FragmentIntro.newInstance(r.getString(R.string.intro_signin_title), r.getString(R.string.intro_signing_description), R.drawable.ic_intro_permissions, Color.parseColor("#cc3333"), window, if (Signin.isSignedIn) null else googleSigninSlideCallback))
        addSlide(FragmentIntro.newInstance(r.getString(R.string.intro_activites_title), r.getString(R.string.intro_activities_description), R.drawable.ic_intro_activites, Color.parseColor("#007b0c"), window, null))
    }

    private fun trackingDialogResponse(option: Int) {
        if (option > 0 && Build.VERSION.SDK_INT >= 23 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestedTracking = option
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
        } else {
            Preferences.getPref(this).edit().putInt(Preferences.PREF_AUTO_TRACKING, option).apply()
            autoUploadDialog!!.show()
        }
    }

    private fun nextSlide(currentSlide: Int) {
        if (progress == currentSlide)
            pager.goToNextSlide()
    }

    /**
     * Checks all required permissions
     *
     * @return true if all permissions are granted
     */
    private fun CheckAllTrackingPermissions(): Boolean {
        if (Build.VERSION.SDK_INT > 22) {
            val permissions = ArrayList<String>()
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)

            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
                permissions.add(android.Manifest.permission.READ_PHONE_STATE)

            if (permissions.size == 0)
                return true

            requestPermissions(permissions.toTypedArray(), 0)
        }
        return false
    }

    override fun onSlideChanged(oldFragment: Fragment?, newFragment: Fragment?) {
        super.onSlideChanged(oldFragment, newFragment)
        currentFragment = newFragment
        if (currentFragment != null) {
            //no check to ensure further changes handle this case
            val fragmentIntro = currentFragment as FragmentIntro?
            if (fragmentIntro!!.hasCallback())
                setSwipeLock(true)
            else
                setSwipeLock(false)
        }
    }

    override fun onDonePressed(currentFragment: Fragment?) {
        Preferences.getPref(this).edit().putBoolean(Preferences.PREF_HAS_BEEN_LAUNCHED, true).apply()
        if (isTaskRoot)
            startActivity(Intent(this, StandardUIActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            val success = grantResults[0] == PackageManager.PERMISSION_GRANTED
            Preferences.getPref(this).edit().putInt(Preferences.PREF_AUTO_TRACKING, if (success) requestedTracking else 0).apply()

            autoUploadDialog!!.show()
        }
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == Signin.RC_SIGN_IN) {
            Signin.onSignResult(this, resultCode, data)
        }
    }
}