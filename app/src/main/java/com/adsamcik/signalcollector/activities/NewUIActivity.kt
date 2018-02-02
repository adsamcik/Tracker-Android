package com.adsamcik.signalcollector.activities

import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Bundle
import android.support.design.widget.CoordinatorLayout
import android.support.v7.app.AppCompatActivity
import com.adsamcik.signalcollector.components.InfoComponent


class NewUIActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_ui)
        val root = findViewById<CoordinatorLayout>(R.id.root)

        val animation = ValueAnimator.ofArgb(Color.parseColor("#166f72"), Color.parseColor("#2e4482"))
        animation.duration = 5000
        animation.repeatMode = ValueAnimator.REVERSE
        animation.repeatCount = ValueAnimator.INFINITE
        animation.addUpdateListener { root.setBackgroundColor(it.animatedValue as Int) }
        animation.start()

        val wifiComponent = findViewById<InfoComponent>(R.id.tracker_wifi_component)
        wifiComponent.addSecondaryText("found them 5meters ago")
        wifiComponent.addPrimaryText("56 wifi nearby")

        val cellComponent = findViewById<InfoComponent>(R.id.tracker_cell_component)
        cellComponent.addPrimaryText("Healthy LTE, you fed it well")
    }


}
