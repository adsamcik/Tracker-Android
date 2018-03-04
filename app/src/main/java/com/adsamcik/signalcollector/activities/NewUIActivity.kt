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
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ListView
import androidx.content.edit
import androidx.view.children
import com.adsamcik.draggable.DragAxis
import com.adsamcik.draggable.DragTargetAnchor
import com.adsamcik.draggable.DraggablePayload
import com.adsamcik.draggable.Offset
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.components.InfoComponent
import com.adsamcik.signalcollector.fragments.FragmentNewActivities
import com.adsamcik.signalcollector.fragments.FragmentNewMap
import com.adsamcik.signalcollector.fragments.FragmentNewStats
import com.adsamcik.signalcollector.test.useMock
import com.adsamcik.signalcollector.uitools.ColorManager
import com.adsamcik.signalcollector.uitools.ColorSupervisor
import com.adsamcik.signalcollector.uitools.ColorView
import com.adsamcik.signalcollector.utility.*
import com.crashlytics.android.Crashlytics
import com.google.android.gms.location.LocationServices
import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator
import com.luckycatlabs.sunrisesunset.dto.Location
import com.takusemba.spotlight.SimpleTarget
import com.takusemba.spotlight.Spotlight
import io.fabric.sdk.android.Fabric
import kotlinx.android.synthetic.main.activity_new_ui.*
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import java.util.*


class NewUIActivity : FragmentActivity() {
    private var colorManager: ColorManager? = null
    private var themeLocationRequestCode = 4513

    private var tutorialActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Fabric.with(this, Crashlytics())
        if (Build.VERSION.SDK_INT >= 26)
            NotificationChannels.prepareChannels(this)

        setContentView(R.layout.activity_new_ui)

        initializeColors()
        //ColorSupervisor.addColors(Color.parseColor("#166f72"), Color.parseColor("#2e4482"), Color.parseColor("#ffc100"), Color.parseColor("#fff400"))
        //ColorSupervisor.addColors(Color.parseColor("#cccccc"), Color.parseColor("#2e4482"), Color.parseColor("#ffc100"), Color.parseColor("#fff400"))

        val display = windowManager.defaultDisplay
        val size = Point()
        display.getRealSize(size)

        val dp = Assist.dpToPx(this, 1)

        statsButton.dragAxis = DragAxis.X
        statsButton.setTarget(root, DragTargetAnchor.RightTop)
        statsButton.setTargetOffsetDp(Offset(56))
        statsButton.targetTranslationZ = dp * 7f
        statsButton.extendTouchAreaBy(dp * 56, 0, 0, 0)

        val statsPayload = DraggablePayload(this, FragmentNewStats::class.java, root, root)
        statsPayload.initialTranslation = Point(-size.x, 0)
        statsPayload.backgroundColor = Color.WHITE
        statsPayload.targetTranslationZ = dp * 6f
        statsPayload.onInitialized = {
            val recycler = it.view!!.findViewById<ListView>(R.id.stats_list_view)
            colorManager!!.watchRecycler(ColorView(recycler, 1, true, true))
        }
        statsPayload.onBeforeDestroyed = {
            val recycler = it.view!!.findViewById<ListView>(R.id.stats_list_view)
            colorManager!!.stopWatchingRecycler(recycler)
        }
        statsButton.addPayload(statsPayload)

        activityButton.dragAxis = DragAxis.X
        activityButton.setTarget(root, DragTargetAnchor.LeftTop)
        activityButton.setTargetOffsetDp(Offset(-56))
        activityButton.targetTranslationZ = dp * 7f
        activityButton.extendTouchAreaBy(0, 0, dp * 56, 0)

        val activityPayload = DraggablePayload(this, FragmentNewActivities::class.java, root, root)
        activityPayload.initialTranslation = Point(size.x, 0)
        activityPayload.backgroundColor = Color.WHITE
        activityPayload.targetTranslationZ = dp * 6f
        activityPayload.onInitialized = { colorManager!!.watchElement(ColorView(it.view!!, 1, true, true)) }

        activityButton.addPayload(activityPayload)

        mapDraggable.dragAxis = DragAxis.Y
        mapDraggable.setTarget(root, DragTargetAnchor.MiddleTop)
        mapDraggable.setTargetOffsetDp(Offset(56))
        mapDraggable.extendTouchAreaBy(dp * 32)
        mapDraggable.targetTranslationZ = 18f * dp

        val mapPayload = DraggablePayload(this, FragmentNewMap::class.java, root, root)
        mapPayload.initialTranslation = Point(0, size.y)
        mapPayload.backgroundColor = Color.WHITE
        mapPayload.setTranslationZ(13f * dp)
        mapPayload.destroyPayloadAfter = (30 * Constants.SECOND_IN_MILLISECONDS).toLong()
        mapDraggable.addPayload(mapPayload)

        settingsButton.setOnClickListener { startActivity<SettingsActivity> { } }

        initializeColorElements()

        if (useMock)
            mock()

        if (!Preferences.getPref(this).getBoolean(getString(R.string.tutorial_seen_key), false))
            launch {
                delay(1000)
                launch(UI) {
                    startTutorial()
                }
            }
    }

    private fun mock() {
        val component = (layoutInflater.inflate(R.layout.template_info_component, content) as ViewGroup).children.last() as InfoComponent
        val drawable = getDrawable(R.drawable.ic_network_wifi_24dp)
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        component.setTitle(drawable, getString(R.string.wifi))
        component.addSecondaryText("Updated 5m before collection")
        component.addPrimaryText("150 WiFi's in range")
        component.addSecondaryText("MOCK")

        launch {
            delay(5000)
            launch(UI) {
                val component2 = (layoutInflater.inflate(R.layout.template_info_component, content) as ViewGroup).children.last() as InfoComponent
                val drawable2 = getDrawable(R.drawable.ic_network_cell_black_24dp)
                drawable2.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
                component2.setTitle(drawable, getString(R.string.cell))
                component2.addPrimaryText("LTE - 140 asu")
                component2.addSecondaryText("14 cell towers in range")
                component2.addSecondaryText("MOCK")
                colorManager?.notififyChangeOn(content)
            }
        }

        time.text = DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        accuracy.text = getString(R.string.info_accuracy, 5)
        altitude.text = getString(R.string.info_altitude, 5)
        collection_count.text = getString(R.string.info_collections, 56)
        data_size.text = getString(R.string.info_collected, Assist.humanReadableByteCount(654321, true))

        colorManager?.notififyChangeOn(content)
    }

    private fun initializeColorElements() {
        colorManager = ColorSupervisor.createColorManager(this)
        val colorManager = colorManager!!

        colorManager.watchElement(ColorView(root, 0, false))
        colorManager.watchElement(ColorView(topPanelLayout, 1, true, false))
        colorManager.watchElement(topInfoBar)
        colorManager.watchElement(ColorView(content, 1, true, false, true))

        colorManager.watchElement(ColorView(statsButton, 1, false, false, false, true))
        colorManager.watchElement(ColorView(mapDraggable, 1, false, false, false, true))
        colorManager.watchElement(ColorView(activityButton, 1, false, false, false, true))

        ColorSupervisor.ensureUpdate()
    }

    private fun startTutorial() {
        tutorialActive = true

        //val mapDraggableTarget = SimpleTarget.Builder(activity).setPoint(mapDraggable.x, mapDraggable.y).setTitle("Map holder").setDescription("Drag this up to pull up the map").build()
        val welcome = SimpleTarget.Builder(this)
                .setTitle(getString(R.string.tutorial_welcome_title))
                .setRadius(0.00001f)
                .setDescription(getString(R.string.tutorial_welcome_description)).build()

        var radius = Math.sqrt(Math.pow(settingsButton.height.toDouble(), 2.0) + Math.pow(settingsButton.width.toDouble(), 2.0)) / 2
        val settingsButtonTarget = SimpleTarget.Builder(this)
                .setPoint(settingsButton.x + settingsButton.pivotX, settingsButton.y + settingsButton.pivotY)
                .setTitle(getString(R.string.tutorial_settings_title))
                .setRadius(radius.toFloat())
                .setDescription(getString(R.string.tutorial_settings_description)).build()

        radius = Math.sqrt(Math.pow(statsButton.height.toDouble(), 2.0) + Math.pow(statsButton.width.toDouble(), 2.0)) / 2
        val statsButtonTarget = SimpleTarget.Builder(this)
                .setPoint(statsButton.x + statsButton.pivotX, statsButton.y + statsButton.pivotY)
                .setTitle(getString(R.string.tutorial_stats_title))
                .setRadius(radius.toFloat())
                .setDescription(getString(R.string.tutorial_stats_description)).build()

        radius = Math.sqrt(Math.pow(activityButton.height.toDouble(), 2.0) + Math.pow(activityButton.width.toDouble(), 2.0)) / 2
        val activitiesButtonTarget = SimpleTarget.Builder(this)
                .setPoint(activityButton.x + activityButton.pivotX, activityButton.y + activityButton.pivotY)
                .setTitle(getString(R.string.tutorial_activity_title))
                .setRadius(radius.toFloat())
                .setDescription(getString(R.string.tutorial_activity_description)).build()

        val mapButtonTarget = SimpleTarget.Builder(this)
                .setPoint(mapDraggable.x + mapDraggable.pivotX, mapDraggable.y + mapDraggable.pivotY)
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

    override fun onDestroy() {
        super.onDestroy()
        ColorSupervisor.recycleColorManager(colorManager!!)
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
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED })
                initializeSunriseSunset()
        }
    }
}
