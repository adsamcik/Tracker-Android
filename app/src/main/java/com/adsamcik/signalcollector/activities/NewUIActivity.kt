package com.adsamcik.signalcollector.activities

import android.os.Bundle
import android.support.design.widget.CoordinatorLayout
import android.support.v7.app.AppCompatActivity
import com.adsamcik.draggable.DragAxis
import com.adsamcik.draggable.DragTargetAnchor
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.uitools.ColorManager
import kotlinx.android.synthetic.main.activity_new_ui.*


class NewUIActivity : AppCompatActivity() {
    var colorManager: ColorManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_ui)

        colorManager = ColorManager(root, this)
        val colorManager = colorManager!!

        //colorManager.addColors(Color.parseColor("#166f72"), Color.parseColor("#2e4482"), Color.parseColor("#ffc100"), Color.parseColor("#fff400"))
        //colorManager.addColors(Color.parseColor("#cccccc"), Color.parseColor("#2e4482"), Color.parseColor("#ffc100"), Color.parseColor("#fff400"))

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

        statsButton.setDrag(DragAxis.X)
        statsButton.setTarget(root, DragTargetAnchor.TopRight, -10)

        activityButton.setDrag(DragAxis.X)
        activityButton.setTarget(root, DragTargetAnchor.TopLeft, -10)

        mapDraggable.setDrag(DragAxis.Y)
        mapDraggable.setTarget(root, DragTargetAnchor.Top, 64)

        //findViewById<ViewStub>(R.id.stub_import).inflate()

        /*colorManager.watchElement(wifiComponent)
        colorManager.watchElement(cellComponent)
        colorManager.watchElement(demo2Component)
        colorManager.watchElement(demoComponent)
        colorManager.watchElement(findViewById(R.id.top_panel_layout))
        colorManager.watchElement(findViewById(R.id.top_info_bar))*/
    }


}
