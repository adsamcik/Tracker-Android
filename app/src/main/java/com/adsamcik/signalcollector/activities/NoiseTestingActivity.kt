package com.adsamcik.signalcollector.activities

import android.app.Activity
import android.os.AsyncTask
import android.os.Bundle
import android.util.MutableInt
import android.view.View
import android.widget.*
import com.adsamcik.signalcollector.NoiseTracker
import com.adsamcik.signalcollector.utility.Preferences
import java.lang.ref.WeakReference
import java.util.*

class NoiseTestingActivity : DetailActivity() {
    private var noiseGetter: NoiseGetter? = null
    private val arrayList = ArrayList<String>()

    private val delayBetweenCollections = MutableInt(3)


    override fun onCreate(savedInstanceState: Bundle?) {
        Preferences.setTheme(this)
        super.onCreate(savedInstanceState)

        val v = layoutInflater.inflate(R.layout.layout_noise_testing, createContentParent(false))
        val startStopButton = findViewById<Button>(R.id.noiseTestStartStopButton)

        setTitle(R.string.noise)

        val sampleIntervalTV = v.findViewById<TextView>(R.id.dev_text_noise_sample_size)

        val seekBar = v.findViewById<SeekBar>(R.id.dev_noise_sample_rate_seek_bar)
        seekBar.max = 9
        seekBar.incrementProgressBy(1)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                delayBetweenCollections.value = progress + 1
                sampleIntervalTV.text = getString(R.string.x_second_short, delayBetweenCollections.value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {

            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {

            }
        })

        seekBar.progress = delayBetweenCollections.value - 1

        val adapter = ArrayAdapter(this, R.layout.spinner_item, arrayList)
        val listView = v.findViewById<ListView>(R.id.dev_noise_list_view)
        listView.adapter = adapter

        startStopButton!!.setOnClickListener { _ ->
            if (noiseGetter == null) {
                noiseGetter = NoiseGetter(this, adapter, listView, delayBetweenCollections)
                noiseGetter!!.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
                startStopButton.text = getString(R.string.stop)
            } else {
                noiseGetter!!.cancel(false)
                noiseGetter = null
                startStopButton.text = getString(R.string.start)
            }
        }

        findViewById<View>(R.id.dev_noise_clear_list).setOnClickListener { _ -> adapter.clear() }

    }


    override fun onDestroy() {
        super.onDestroy()
        noiseGetter?.cancel(true)
    }


    private class NoiseGetter constructor(activity: Activity, private val adapter: ArrayAdapter<String>, listView: ListView, private val delayBetweenSamples: MutableInt?) : AsyncTask<Void, Void, Void>() {
        private val noiseTracker: NoiseTracker = NoiseTracker(activity)

        private val activity: WeakReference<Activity> = WeakReference(activity)
        private val listView: WeakReference<ListView> = WeakReference(listView)

        override fun doInBackground(vararg params: Void): Void? {
            noiseTracker.start()
            while (delayBetweenSamples != null) {
                try {
                    Thread.sleep((delayBetweenSamples.value * 1000).toLong())
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }

                if (isCancelled)
                    break

                val sample = noiseTracker.getSample(delayBetweenSamples.value)
                if (sample.toInt() != -1) {
                    val activity = this.activity.get()
                    val listView = this.listView.get()
                    if (activity != null && listView != null)
                        activity.runOnUiThread {
                            adapter.add(Integer.toString(sample.toInt()))
                            listView.smoothScrollToPosition(adapter.count - 1)
                        }
                }
                //todo add snackbar if noise tracker failed to initialize
            }
            noiseTracker.stop()
            return null
        }

        override fun onCancelled(aVoid: Void?) {
            super.onCancelled(aVoid)
            if (noiseTracker.isRunning)
                noiseTracker.stop()
        }
    }
}
