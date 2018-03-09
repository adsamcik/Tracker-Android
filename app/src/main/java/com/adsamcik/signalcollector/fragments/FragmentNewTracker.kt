package com.adsamcik.signalcollector.fragments

import android.os.Bundle
import android.support.design.widget.FloatingActionButton
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentActivity
import android.support.v4.content.ContextCompat.getDrawable
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.view.children
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activities.SettingsActivity
import com.adsamcik.signalcollector.components.InfoComponent
import com.adsamcik.signalcollector.interfaces.ITabFragment
import com.adsamcik.signalcollector.test.useMock
import com.adsamcik.signalcollector.uitools.ColorManager
import com.adsamcik.signalcollector.uitools.ColorSupervisor
import com.adsamcik.signalcollector.uitools.ColorView
import com.adsamcik.signalcollector.uitools.dpAsPx
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.startActivity
import kotlinx.android.synthetic.main.fragment_new_tracker.*
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch

class FragmentNewTracker : Fragment(), ITabFragment {
    private lateinit var colorManager: ColorManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_new_tracker, container, false)
    }

    override fun onStart() {
        super.onStart()
        retainInstance = true
        initializeColorElements()

        settingsButton.setOnClickListener { startActivity<SettingsActivity> { } }

        if (useMock)
            mock()
    }

    override fun onDestroy() {
        super.onDestroy()
        ColorSupervisor.recycleColorManager(colorManager)
    }

    override fun onResume() {
        super.onResume()
        val orientation = Assist.orientation(context!!)
        if (orientation == Surface.ROTATION_90 || orientation == Surface.ROTATION_270) {
            include.setPadding(72.dpAsPx, 0, 72.dpAsPx, 0)
        }
    }

    private fun mock() {
        val context = context!!
        val component = (layoutInflater.inflate(R.layout.template_component_info, content) as ViewGroup).children.last() as InfoComponent
        val drawable = getDrawable(context, R.drawable.ic_network_wifi_24dp)!!
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        component.setTitle(drawable, getString(R.string.wifi))
        component.addSecondaryText("Updated 5m before collection")
        component.addPrimaryText("150 WiFi's in range")
        component.addSecondaryText("MOCK")

        launch {
            delay(2000)
            launch(UI) {
                val component2 = (layoutInflater.inflate(R.layout.template_component_info, content) as ViewGroup).children.last() as InfoComponent
                val drawable2 = getDrawable(context, R.drawable.ic_network_cell_black_24dp)!!
                drawable2.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
                component2.setTitle(drawable, getString(R.string.cell))
                component2.addPrimaryText("LTE - 140 asu")
                component2.addSecondaryText("14 cell towers in range")
                component2.addSecondaryText("MOCK")
                colorManager.notififyChangeOn(content)
            }
        }

        time.text = DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        accuracy.text = getString(R.string.info_accuracy, 5)
        altitude.text = getString(R.string.info_altitude, 5)
        collection_count.text = getString(R.string.info_collections, 56)
        data_size.text = getString(R.string.info_collected, Assist.humanReadableByteCount(654321, true))

        colorManager.notififyChangeOn(content)
    }

    private fun initializeColorElements() {
        colorManager = ColorSupervisor.createColorManager(context!!)
        colorManager.watchElement(ColorView(topPanelLayout, 1, true, false))
        colorManager.watchElement(topInfoBar)
        colorManager.watchElement(ColorView(content, 1, true, false, true))
    }

    override fun onEnter(activity: FragmentActivity, fabOne: FloatingActionButton, fabTwo: FloatingActionButton) {
        //TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onLeave(activity: FragmentActivity) {
        //TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onPermissionResponse(requestCode: Int, success: Boolean) {
        //TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onHomeAction() {
        //TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

}