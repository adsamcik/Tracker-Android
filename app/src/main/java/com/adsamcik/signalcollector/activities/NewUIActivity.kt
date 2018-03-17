package com.adsamcik.signalcollector.activities

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.support.v4.app.FragmentActivity
import android.support.v4.content.ContextCompat
import android.support.v4.graphics.ColorUtils
import android.view.MotionEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ListView
import androidx.content.edit
import com.adsamcik.draggable.*
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.enums.CloudStatus
import com.adsamcik.signalcollector.extensions.transaction
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.fragments.FragmentNewActivities
import com.adsamcik.signalcollector.fragments.FragmentNewMap
import com.adsamcik.signalcollector.fragments.FragmentNewStats
import com.adsamcik.signalcollector.fragments.FragmentNewTracker
import com.adsamcik.signalcollector.jobs.UploadJobService
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.services.ActivityService
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.uitools.*
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Constants
import com.adsamcik.signalcollector.utility.NotificationChannels
import com.adsamcik.signalcollector.utility.Preferences
import com.google.android.gms.location.LocationServices
import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator
import com.luckycatlabs.sunrisesunset.dto.Location
import com.takusemba.spotlight.SimpleTarget
import com.takusemba.spotlight.Spotlight
import kotlinx.android.synthetic.main.activity_new_ui.*
import kotlinx.android.synthetic.main.fragment_new_tracker.*
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import java.util.*


class NewUIActivity : FragmentActivity() {
    private lateinit var colorManager: ColorManager
    private var themeLocationRequestCode = 4513

    private var tutorialActive = false

    private var draggableOriginalMargin = Int.MIN_VALUE


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_ui)

        if (Build.VERSION.SDK_INT >= 26)
            NotificationChannels.prepareChannels(this)

        Assist.initialize(this)
        Signin.signIn(this, null, true)

        if (Assist.checkPlayServices(this))
            ActivityService.requestAutoTracking(this, javaClass)

        initializeColors()
        initializeButtons()
        initializeColorElements()

        if (Network.cloudStatus == CloudStatus.UNKNOWN) {
            val scheduleSource = UploadJobService.getUploadScheduled(this)
            when (scheduleSource) {
                UploadJobService.UploadScheduleSource.NONE -> Network.cloudStatus = if (DataStore.sizeOfData(this) >= Constants.MIN_USER_UPLOAD_FILE_SIZE) CloudStatus.SYNC_AVAILABLE else CloudStatus.NO_SYNC_REQUIRED
                UploadJobService.UploadScheduleSource.BACKGROUND, UploadJobService.UploadScheduleSource.USER -> Network.cloudStatus = CloudStatus.SYNC_SCHEDULED
            }
        }

        if (!Preferences.getPref(this).getBoolean(getString(R.string.tutorial_seen_key), false))
            launch {
                delay(1000)
                launch(UI) {
                    startTutorial()
                }
            }

        supportFragmentManager.transaction {
            replace(R.id.root, FragmentNewTracker())
        }
    }

    override fun onResume() {
        super.onResume()
        initializeButtonsPosition()
        initializeColors()
    }

    private fun initializeButtons() {
        val display = windowManager.defaultDisplay
        val realSize = Point()
        val size = Point()
        display.getRealSize(realSize)
        display.getSize(size)

        val dp = Assist.dpToPx(this, 1)

        button_stats.dragAxis = DragAxis.X
        button_stats.setTarget(root, DragTargetAnchor.RightTop)
        button_stats.setTargetOffsetDp(Offset(56))
        button_stats.targetTranslationZ = 8.dpAsPx.toFloat()
        button_stats.extendTouchAreaBy(dp * 56, 0, 0, 0)

        val statsPayload = DraggablePayload(this, FragmentNewStats::class.java, root, root)
        statsPayload.initialTranslation = Point(-size.x, 0)
        statsPayload.backgroundColor = Color.WHITE
        statsPayload.targetTranslationZ = 7.dpAsPx.toFloat()
        statsPayload.destroyPayloadAfter = 15 * Constants.SECOND_IN_MILLISECONDS
        statsPayload.onInitialized = {
            val recycler = it.view!!.findViewById<ListView>(R.id.stats_list_view)
            colorManager.watchRecycler(ColorView(recycler, 1, true, true))
        }
        statsPayload.onBeforeDestroyed = {
            val recycler = it.view!!.findViewById<ListView>(R.id.stats_list_view)
            colorManager.stopWatchingRecycler(recycler)
        }
        button_stats.addPayload(statsPayload)

        button_activity.dragAxis = DragAxis.X
        button_activity.setTarget(root, DragTargetAnchor.LeftTop)
        button_activity.setTargetOffsetDp(Offset(-56))
        button_activity.targetTranslationZ = 8.dpAsPx.toFloat()
        button_activity.extendTouchAreaBy(0, 0, dp * 56, 0)

        val activityPayload = DraggablePayload(this, FragmentNewActivities::class.java, root, root)
        activityPayload.initialTranslation = Point(size.x, 0)
        activityPayload.backgroundColor = Color.WHITE
        activityPayload.targetTranslationZ = 7.dpAsPx.toFloat()
        activityPayload.destroyPayloadAfter = 15 * Constants.SECOND_IN_MILLISECONDS
        activityPayload.onInitialized = { colorManager.watchElement(ColorView(it.view!!, 1, true, true)) }

        button_activity.addPayload(activityPayload)

        button_map.dragAxis = DragAxis.Y
        button_map.setTarget(root, DragTargetAnchor.MiddleTop)
        button_map.setTargetOffsetDp(Offset(56))
        button_map.extendTouchAreaBy(32.dpAsPx)
        button_map.targetTranslationZ = 18.dpAsPx.toFloat()

        val mapPayload = DraggablePayload(this, FragmentNewMap::class.java, root, root)
        mapPayload.initialTranslation = Point(0, realSize.y)
        mapPayload.backgroundColor = Color.WHITE
        mapPayload.setTranslationZ(16.dpAsPx.toFloat())
        mapPayload.destroyPayloadAfter = 30 * Constants.SECOND_IN_MILLISECONDS
        button_map.addPayload(mapPayload)
    }

    private fun initializeButtonsPosition() {
        if (draggableOriginalMargin == Int.MIN_VALUE)
            draggableOriginalMargin = button_map.marginBottom

        val (position, navDim) = Assist.navbarSize(this)
        if (navDim.x > navDim.y)
            navDim.x = 0
        else
            navDim.y = 0

        button_map.setMargin(0, 0, 0, draggableOriginalMargin + navDim.y)

        when (position) {
            Assist.NavBarPosition.RIGHT -> {
                root.setPadding(0, 0, navDim.x, 0)
            }
            Assist.NavBarPosition.LEFT -> {
                root.setPadding(navDim.x, 0, 0, 0)
            }
            else -> root.setPadding(0, 0, 0, 0)
        }
    }

    private fun initializeColorElements() {
        colorManager = ColorSupervisor.createColorManager(this)

        colorManager.watchElement(ColorView(root, 0, false, true, false))

        colorManager.watchElement(ColorView(button_stats, 1, false, false, false, true))
        colorManager.watchElement(ColorView(button_map, 1, false, false, false, true))
        colorManager.watchElement(ColorView(button_activity, 1, false, false, false, true))

        ColorSupervisor.ensureUpdate()
    }

    private fun startTutorial() {
        tutorialActive = true

        //val mapDraggableTarget = SimpleTarget.Builder(activity).setPoint(mapDraggable.x, mapDraggable.y).setTitle("Map holder").setDescription("Drag this up to pull up the map").build()
        val welcome = SimpleTarget.Builder(this)
                .setTitle(getString(R.string.tutorial_welcome_title))
                .setRadius(0.00001f)
                .setDescription(getString(R.string.tutorial_welcome_description)).build()

        var radius = Math.sqrt(Math.pow(button_settings.height.toDouble(), 2.0) + Math.pow(button_settings.width.toDouble(), 2.0)) / 2
        val settingsButtonTarget = SimpleTarget.Builder(this)
                .setPoint(button_settings.x + button_settings.pivotX, button_settings.y + button_settings.pivotY)
                .setTitle(getString(R.string.tutorial_settings_title))
                .setRadius(radius.toFloat())
                .setDescription(getString(R.string.tutorial_settings_description)).build()

        radius = Math.sqrt(Math.pow(button_stats.height.toDouble(), 2.0) + Math.pow(button_stats.width.toDouble(), 2.0)) / 2
        val statsButtonTarget = SimpleTarget.Builder(this)
                .setPoint(button_stats.x + button_stats.pivotX, button_stats.y + button_stats.pivotY)
                .setTitle(getString(R.string.tutorial_stats_title))
                .setRadius(radius.toFloat())
                .setDescription(getString(R.string.tutorial_stats_description)).build()

        radius = Math.sqrt(Math.pow(button_activity.height.toDouble(), 2.0) + Math.pow(button_activity.width.toDouble(), 2.0)) / 2
        val activitiesButtonTarget = SimpleTarget.Builder(this)
                .setPoint(button_activity.x + button_activity.pivotX, button_activity.y + button_activity.pivotY)
                .setTitle(getString(R.string.tutorial_activity_title))
                .setRadius(radius.toFloat())
                .setDescription(getString(R.string.tutorial_activity_description)).build()

        val mapButtonTarget = SimpleTarget.Builder(this)
                .setPoint(button_map.x + button_map.pivotX, button_map.y + button_map.pivotY)
                .setTitle(getString(R.string.tutorial_map_title))
                .setRadius(Assist.dpToPx(this, 48).toFloat())
                .setDescription(getString(R.string.tutorial_map_description)).build()

        Spotlight.with(this)
                .setTargets(welcome, settingsButtonTarget, mapButtonTarget, statsButtonTarget, activitiesButtonTarget)
                .setOverlayColor(ColorUtils.setAlphaComponent(Color.BLACK, 204))
                .setAnimation(AccelerateDecelerateInterpolator())
                .setOnSpotlightEndedListener {
                    tutorialActive = false
                    Preferences.getPref(this).edit {
                        putBoolean(getString(R.string.tutorial_seen_key), true)
                    }
                }
                .start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (button_map.state == DraggableImageButton.State.TARGET)
            outState.putBoolean(MAP_OPENED, true)

        if (button_stats.state == DraggableImageButton.State.TARGET)
            outState.putBoolean(STATS_OPENED, true)

        if (button_activity.state == DraggableImageButton.State.TARGET)
            outState.putBoolean(ACTIVITIES_OPENED, true)

        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        ColorSupervisor.recycleColorManager(colorManager)
    }

    private fun initializeColors() {
        ColorSupervisor.initializeFromPreferences(this)
        initializeSunriseSunset()
    }

    private fun initializeSunriseSunset() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnCompleteListener {
                if (it.isSuccessful) {
                    val loc = it.result
                    if (loc != null) {
                        val (sunrise, sunset) = calculateSunsetSunrise(loc)
                        ColorSupervisor.setSunsetSunrise(sunrise, sunset)
                    }
                }
            }
        } else if (Build.VERSION.SDK_INT >= 23)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), themeLocationRequestCode)
    }

    private fun calculateSunsetSunrise(loc: android.location.Location): Pair<Calendar, Calendar> {
        val calendar = Calendar.getInstance()
        val calculator = SunriseSunsetCalculator(Location(loc.latitude, loc.longitude), calendar.timeZone)
        val sunrise = calculator.getOfficialSunriseCalendarForDate(calendar)
        val sunset = calculator.getOfficialSunsetCalendarForDate(calendar)
        return Pair(sunrise, sunset)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return if (!tutorialActive && root.touchDelegate.onTouchEvent(event))
            true
        else
            super.dispatchTouchEvent(event)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == themeLocationRequestCode) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
                initializeSunriseSunset()
        }
    }

    override fun onBackPressed() {
        when {
            button_map.state == DraggableImageButton.State.TARGET -> button_map.moveToState(DraggableImageButton.State.INITIAL, true, true)
            button_stats.state == DraggableImageButton.State.TARGET -> button_stats.moveToState(DraggableImageButton.State.INITIAL, true, true)
            button_activity.state == DraggableImageButton.State.TARGET -> button_activity.moveToState(DraggableImageButton.State.INITIAL, true, true)
            else -> super.onBackPressed()
        }
    }

    companion object {
        const val MAP_OPENED = "mapopened"
        const val STATS_OPENED = "statsopened"
        const val ACTIVITIES_OPENED = "activitiesopened"
    }
}
