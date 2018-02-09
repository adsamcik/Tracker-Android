package com.adsamcik.signalcollector.activities

import android.os.Bundle
import android.support.design.widget.CoordinatorLayout
import android.support.v7.app.AppCompatActivity
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.components.DraggableImageButton
import com.adsamcik.signalcollector.components.InfoComponent
import com.adsamcik.signalcollector.uitools.ColorManager


class NewUIActivity : AppCompatActivity() {
    var colorManager: ColorManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_ui)
        val root = findViewById<CoordinatorLayout>(R.id.root)

        colorManager = ColorManager(root, this)
        val colorManager = colorManager!!

        //colorManager.addColors(Color.parseColor("#166f72"), Color.parseColor("#2e4482"), Color.parseColor("#ffc100"), Color.parseColor("#fff400"))
        //colorManager.addColors(Color.parseColor("#cccccc"), Color.parseColor("#2e4482"), Color.parseColor("#ffc100"), Color.parseColor("#fff400"))

        val wifiComponent = findViewById<InfoComponent>(R.id.tracker_wifi_component)
        wifiComponent.addSecondaryText("found 6 meters before collection")
        wifiComponent.addPrimaryText("In range of 150 Wifi's")
        wifiComponent.addPrimaryText("In range of 150 Wifi's")
        wifiComponent.addPrimaryText("In range of 150 Wifi's")
        wifiComponent.addPrimaryText("In range of 150 Wifi's")
        wifiComponent.addPrimaryText("In range of 150 Wifi's")
        wifiComponent.addPrimaryText("In range of 150 Wifi's")

        val cellComponent = findViewById<InfoComponent>(R.id.tracker_cell_component)
        cellComponent.addPrimaryText("LTE -89 dbm, 51 asu")
        cellComponent.addPrimaryText("In range of 12 base stations")

        val demo2Component = findViewById<InfoComponent>(R.id.tracker_demo2_component)
        demo2Component.addSecondaryText("found 6 meters before collection")
        demo2Component.addPrimaryText("In range of 150 Wifi's")
        demo2Component.addPrimaryText("In range of 12 base stations")

        val demoComponent = findViewById<InfoComponent>(R.id.tracker_demo_component)
        demoComponent.addPrimaryText("LTE -89 dbm, 51 asu")
        demoComponent.addPrimaryText("In range of 12 base stations")

        val statsButton = findViewById<DraggableImageButton>(R.id.stats_button)
        statsButton.setDrag(DraggableImageButton.DragAxis.X)
        statsButton.setTarget(root, DraggableImageButton.DragTargetAnchor.TopRight, -10)

        val activityButton = findViewById<DraggableImageButton>(R.id.activity_button)
        activityButton.setDrag(DraggableImageButton.DragAxis.X)
        activityButton.setTarget(root, DraggableImageButton.DragTargetAnchor.TopLeft, -10)

        val mapDraggable = findViewById<DraggableImageButton>(R.id.map_draggable)
        mapDraggable.setDrag(DraggableImageButton.DragAxis.Y)
        mapDraggable.setTarget(root, DraggableImageButton.DragTargetAnchor.Top, 64)

        /*colorManager.watchElement(wifiComponent)
        colorManager.watchElement(cellComponent)
        colorManager.watchElement(demo2Component)
        colorManager.watchElement(demoComponent)
        colorManager.watchElement(findViewById(R.id.top_panel_layout))
        colorManager.watchElement(findViewById(R.id.top_info_bar))*/
    }


}
