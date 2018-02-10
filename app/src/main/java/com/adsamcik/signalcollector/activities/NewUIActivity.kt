package com.adsamcik.signalcollector.activities

import android.graphics.Color
import android.graphics.Point
import android.os.Bundle
import android.support.v4.app.FragmentActivity
import android.util.DisplayMetrics
import android.view.View
import com.adsamcik.draggable.DragAxis
import com.adsamcik.draggable.DragTargetAnchor
import com.adsamcik.draggable.DraggablePayload
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.fragments.FragmentActivities
import com.adsamcik.signalcollector.fragments.FragmentNewMap
import com.adsamcik.signalcollector.fragments.FragmentStats
import com.adsamcik.signalcollector.uitools.ColorManager
import kotlinx.android.synthetic.main.activity_new_ui.*
import java.util.*


class NewUIActivity : FragmentActivity() {
    var colorManager: ColorManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_ui)

        colorManager = ColorManager(root as View, this)
        val colorManager = colorManager!!

        colorManager.addColors(Color.parseColor("#166f72"), Color.parseColor("#2e4482"), Color.parseColor("#ffc100"), Color.parseColor("#fff400"))
        colorManager.addColors(Color.parseColor("#cccccc"), Color.parseColor("#2e4482"), Color.parseColor("#ffc100"), Color.parseColor("#fff400"))

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

        statsButton.setDrag(DragAxis.X)
        statsButton.setTarget(root, DragTargetAnchor.TopRight, -10)
        val statsPayload = DraggablePayload(this, FragmentStats::class.java, Point(-size.x, 0), root, DragTargetAnchor.TopRight, 0)
        statsPayload.setBackgroundColor(Color.WHITE)
        statsButton.addPayload(statsPayload)
        statsButton.setTargetTranslationZ(12f)

        activityButton.setDrag(DragAxis.X)
        activityButton.setTarget(root, DragTargetAnchor.TopLeft, -10)
        activityButton.setTargetTranslationZ(12f)

        val activityPayload = DraggablePayload(this, FragmentActivities::class.java, Point(size.x, 0), root, DragTargetAnchor.TopLeft, 0)
        activityPayload.setBackgroundColor(Color.WHITE)
        activityButton.addPayload(activityPayload)

        mapDraggable.setDrag(DragAxis.Y)
        mapDraggable.setTarget(root, DragTargetAnchor.Top, 64)

        val mapPayload = DraggablePayload(this, FragmentNewMap::class.java, Point(0, size.y), root, DragTargetAnchor.TopLeft, 0)
        mapDraggable.addPayload(mapPayload)

        //findViewById<ViewStub>(R.id.stub_import).inflate()

        colorManager.watchElement(trackerWifiComponent)
        colorManager.watchElement(trackerCellComponent)
        colorManager.watchElement(trackerDemo2Component)
        colorManager.watchElement(trackerDemoComponent)
        colorManager.watchElement(topPanelLayout)
        colorManager.watchElement(topInfoBar)
    }


}
