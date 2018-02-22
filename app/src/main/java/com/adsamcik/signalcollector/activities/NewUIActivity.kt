package com.adsamcik.signalcollector.activities

import android.graphics.Color
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.support.v4.app.FragmentActivity
import android.view.MotionEvent
import android.view.View
import android.widget.ListView
import com.adsamcik.draggable.DragAxis
import com.adsamcik.draggable.DragTargetAnchor
import com.adsamcik.draggable.DraggablePayload
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.fragments.FragmentActivities
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
import io.fabric.sdk.android.Fabric
import kotlinx.android.synthetic.main.activity_new_ui.*


class NewUIActivity : FragmentActivity() {
    private var colorManager: ColorManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Fabric.with(this, Crashlytics())
        if (Build.VERSION.SDK_INT >= 26)
            NotificationChannels.prepareChannels(this)

        setContentView(R.layout.activity_new_ui)

        colorManager = ColorSupervisor.createColorManager(this)
        val colorManager = colorManager!!

        colorManager.watchElement(ColorView(root as View, 0, false, true, false))

        ColorSupervisor.addColors(Color.parseColor("#166f72"), Color.parseColor("#2e4482"), Color.parseColor("#ffc100"), Color.parseColor("#fff400"))
        ColorSupervisor.addColors(Color.parseColor("#cccccc"), Color.parseColor("#2e4482"), Color.parseColor("#ffc100"), Color.parseColor("#fff400"))

        trackerWifiComponent.addSecondaryText("found 6 meters before collection")
        trackerWifiComponent.addPrimaryText("In range of 150 Wifi's")
        trackerWifiComponent.addPrimaryText("In range of 150 Wifi's")
        trackerWifiComponent.addPrimaryText("In range of 150 Wifi's")
        trackerWifiComponent.addPrimaryText("In range of 150 Wifi's")
        trackerWifiComponent.addPrimaryText("In range of 150 Wifi's")
        trackerWifiComponent.addPrimaryText("In range of 150 Wifi's")

        trackerCellComponent.addPrimaryText("LTE -89 dbm, 51 asu")
        trackerCellComponent.addPrimaryText("In range of 12 base stations")

        trackerDemo2Component.addSecondaryText("found 6 meters before collection")
        trackerDemo2Component.addPrimaryText("In range of 150 Wifi's")
        trackerDemo2Component.addPrimaryText("In range of 12 base stations")

        trackerDemoComponent.addPrimaryText("LTE -89 dbm, 51 asu")
        trackerDemoComponent.addPrimaryText("In range of 12 base stations")

        val display = windowManager.defaultDisplay
        val size = Point()
        display.getRealSize(size)

        val dp = Assist.dpToPx(this, 1)

        statsButton.dragAxis = DragAxis.X
        statsButton.setTarget(root, DragTargetAnchor.TopRight, -56)
        statsButton.targetTranslationZ = dp * 9f
        statsButton.increaseTouchAreaBy(dp * 28, 0, 0, 0)

        val statsPayload = DraggablePayload(this, FragmentNewStats::class.java, Point(-size.x, 0), root, DragTargetAnchor.TopRight, 0)
        statsPayload.backgroundColor = Color.WHITE
        statsPayload.targetTranslationZ = dp * 8f
        statsPayload.onInitialized = {
            val recycler = it.view!!.findViewById<ListView>(R.id.stats_list_view)
            colorManager.watchRecycler(ColorView(recycler, 1, true, true))
        }
        statsPayload.onBeforeDestroyed = {
            val recycler = it.view!!.findViewById<ListView>(R.id.stats_list_view)
            colorManager.stopWatchingRecycler(recycler)
        }
        statsButton.addPayload(statsPayload)

        activityButton.dragAxis = DragAxis.X
        activityButton.setTarget(root, DragTargetAnchor.TopLeft, -56)
        activityButton.targetTranslationZ = dp * 9f
        activityButton.increaseTouchAreaBy(0, 0, dp * 28, 0)

        val activityPayload = DraggablePayload(this, FragmentActivities::class.java, Point(size.x, 0), root, DragTargetAnchor.TopLeft, 0)
        activityPayload.backgroundColor = Color.WHITE
        activityPayload.targetTranslationZ = dp * 8f
        activityPayload.onInitialized = { colorManager.watchElement(ColorView(it.view!!, 1, true, true)) }

        activityButton.addPayload(activityPayload)

        mapDraggable.dragAxis = DragAxis.Y
        mapDraggable.setTarget(root, DragTargetAnchor.Top, 64)
        mapDraggable.increaseTouchAreaBy(dp * 32)
        mapDraggable.targetTranslationZ = 13f * dp

        val mapPayload = DraggablePayload(this, FragmentNewMap::class.java, Point(0, size.y), root, DragTargetAnchor.TopLeft, 0)
        mapPayload.backgroundColor = Color.WHITE
        mapPayload.setTranslationZ(12f * dp)
        mapPayload.destroyPayloadAfter = (30 * Constants.SECOND_IN_MILLISECONDS).toLong()
        mapPayload.onInitialized = {
            val map = it.view!!.findViewById(R.id.map_search) as View
            colorManager.watchElement(ColorView(map, 2, true, true))
        }
        mapPayload.onBeforeDestroyed = { colorManager.stopWatchingElement(it.view!!.findViewById(R.id.map_search)) }
        mapDraggable.addPayload(mapPayload)

        buttonSettings.setOnClickListener { startActivity<SettingsActivity> { } }

        //findViewById<ViewStub>(R.id.stub_import).inflate()

        colorManager.watchElement(trackerWifiComponent)
        colorManager.watchElement(trackerCellComponent)
        colorManager.watchElement(trackerDemo2Component)
        colorManager.watchElement(trackerDemoComponent)
        colorManager.watchElement(topPanelLayout)
        colorManager.watchElement(topInfoBar)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return if (root.touchDelegate.onTouchEvent(event))
            true
        else
            super.dispatchTouchEvent(event)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)


    }

}
