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
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ListView
import com.adsamcik.draggable.DragAxis
import com.adsamcik.draggable.DragTargetAnchor
import com.adsamcik.draggable.DraggablePayload
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.fragments.FragmentNewActivities
import com.adsamcik.signalcollector.fragments.FragmentNewMap
import com.adsamcik.signalcollector.fragments.FragmentNewStats
import com.adsamcik.signalcollector.uitools.ColorManager
import com.adsamcik.signalcollector.uitools.ColorSupervisor
import com.adsamcik.signalcollector.uitools.ColorView
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Constants
import com.adsamcik.signalcollector.utility.NotificationChannels
import com.adsamcik.signalcollector.utility.startActivity
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
        statsButton.setTarget(root, DragTargetAnchor.TopRight, -56)
        statsButton.targetTranslationZ = dp * 7f
        statsButton.increaseTouchAreaBy(dp * 56, 0, 0, 0)

        val statsPayload = DraggablePayload(this, FragmentNewStats::class.java, Point(-size.x, 0), root, DragTargetAnchor.TopRight, 0)
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
        activityButton.setTarget(root, DragTargetAnchor.TopLeft, -56)
        activityButton.targetTranslationZ = dp * 7f
        activityButton.increaseTouchAreaBy(0, 0, dp * 56, 0)

        val activityPayload = DraggablePayload(this, FragmentNewActivities::class.java, Point(size.x, 0), root, DragTargetAnchor.TopLeft, 0)
        activityPayload.backgroundColor = Color.WHITE
        activityPayload.targetTranslationZ = dp * 6f
        activityPayload.onInitialized = { colorManager!!.watchElement(ColorView(it.view!!, 1, true, true)) }

        activityButton.addPayload(activityPayload)

        mapDraggable.dragAxis = DragAxis.Y
        mapDraggable.setTarget(root, DragTargetAnchor.Top, 64)
        mapDraggable.increaseTouchAreaBy(dp * 32)
        mapDraggable.targetTranslationZ = 18f * dp

        val mapPayload = DraggablePayload(this, FragmentNewMap::class.java, Point(0, size.y), root, DragTargetAnchor.TopLeft, 0)
        mapPayload.backgroundColor = Color.WHITE
        mapPayload.setTranslationZ(13f * dp)
        mapPayload.destroyPayloadAfter = (5 * Constants.SECOND_IN_MILLISECONDS).toLong()
        mapPayload.onInitialized = {
            val mapUIParent = it.view!!.findViewById(R.id.map_ui_parent) as View
            colorManager!!.watchElement(ColorView(mapUIParent, 2, true, false, true))
        }
        mapPayload.onBeforeDestroyed = { colorManager!!.stopWatchingElement(R.id.map_search) }
        mapDraggable.addPayload(mapPayload)

        settingsButton.setOnClickListener { startActivity<SettingsActivity> { } }

        initializeColorElements()

        launch {
            delay(1000)
            launch(UI) {
                startTutorial()
            }
        }
    }

    private fun initializeColorElements() {
        colorManager = ColorSupervisor.createColorManager(this)
        val colorManager = colorManager!!

        colorManager.watchElement(ColorView(root as View, 0, false, true, false))
        colorManager.watchElement(topPanelLayout)
        colorManager.watchElement(topInfoBar)

        colorManager.watchElement(ColorView(statsButton, 3, false, false, false, true))
        colorManager.watchElement(ColorView(mapDraggable, 3, false, false, false, true))
        colorManager.watchElement(ColorView(activityButton, 3, false, false, false, true))
    }

    private fun startTutorial() {

        //val mapDraggableTarget = SimpleTarget.Builder(activity).setPoint(mapDraggable.x, mapDraggable.y).setTitle("Map holder").setDescription("Drag this up to pull up the map").build()
        val radius = Math.sqrt(Math.pow(statsButton.height.toDouble(), 2.0) + Math.pow(statsButton.width.toDouble(), 2.0)) / 2
        val statsButtonTarget = SimpleTarget.Builder(this)
                .setPoint(statsButton.x + statsButton.pivotX, statsButton.y + statsButton.pivotY)
                .setTitle("Stats")
                .setRadius(radius.toFloat())
                .setDescription("Drag this to the left to access stats").build()
        Spotlight.with(this).setTargets(statsButtonTarget).setOverlayColor(ColorUtils.setAlphaComponent(Color.BLACK, 204)).setAnimation(DecelerateInterpolator(2f)).start()
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
        return if (root.touchDelegate.onTouchEvent(event))
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
