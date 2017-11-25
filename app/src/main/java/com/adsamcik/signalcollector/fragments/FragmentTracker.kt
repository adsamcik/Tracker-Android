package com.adsamcik.signalcollector.fragments

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.support.design.widget.FloatingActionButton
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentActivity
import android.support.v4.content.ContextCompat
import android.support.v7.widget.CardView
import android.text.format.DateFormat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import com.adsamcik.signalcollector.BuildConfig
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.enums.CloudStatus
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.interfaces.ICallback
import com.adsamcik.signalcollector.interfaces.ITabFragment
import com.adsamcik.signalcollector.jobs.UploadJobService
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.services.TrackerService
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.utility.*
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crash.FirebaseCrash

class FragmentTracker : Fragment(), ITabFragment {
    private var layoutCell: CardView? = null
    private var layoutWifi: CardView? = null
    private var layoutOther: CardView? = null
    private var textTime: TextView? = null
    private var textPosition: TextView? = null
    private var textAccuracy: TextView? = null
    private var textWifiCount: TextView? = null
    private var textWifiCollection: TextView? = null
    private var textCurrentCell: TextView? = null
    private var textCellCount: TextView? = null
    private var textActivity: TextView? = null
    private var textCollected: TextView? = null
    private var textNoise: TextView? = null
    private var textCollections: TextView? = null
    private var progressBar: ProgressBar? = null

    private var pauseToPlay: AnimatedVectorDrawable? = null
    private var playToPause: AnimatedVectorDrawable? = null
    private var fabTrack: FloatingActionButton? = null
    private var fabUp: FloatingActionButton? = null

    private var lastWifiTime: Long = 0

    private var handler: Handler? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_tracker, container, false)

        textAccuracy = view.findViewById(R.id.textAccuracy)
        textPosition = view.findViewById(R.id.textPosition)
        textCellCount = view.findViewById(R.id.textCellCount)
        textCurrentCell = view.findViewById(R.id.textCurrentCell)
        textWifiCount = view.findViewById(R.id.textWifiCount)
        textWifiCollection = view.findViewById(R.id.textWifiCollection)
        textTime = view.findViewById(R.id.textTime)
        textNoise = view.findViewById(R.id.textNoise)
        textActivity = view.findViewById(R.id.textActivity)
        textCollected = view.findViewById(R.id.textCollected)
        textCollections = view.findViewById(R.id.textCollections)

        layoutWifi = view.findViewById(R.id.layout_wifi)
        layoutCell = view.findViewById(R.id.layout_cells)
        layoutOther = view.findViewById(R.id.layout_other)

        layoutWifi!!.visibility = View.GONE
        layoutCell!!.visibility = View.GONE
        layoutOther!!.visibility = View.GONE

        val context = context

        if (BuildConfig.DEBUG && context == null)
            throw RuntimeException()

        updateData(context!!)

        return view
    }


    /**
     * Updates collected data text
     *
     * @param collected amount of collected data
     */
    private fun setCollected(context: Context, collected: Long, count: Int) {
        if (textCollected != null) {
            val resources = context.resources
            textCollected!!.text = resources.getString(R.string.main_collected, Assist.humanReadableByteCount(collected, true))
            textCollections!!.text = resources.getQuantityString(R.plurals.main_collections, count, count)
        }
    }

    /**
     * 0 - start tracking icon
     * 1 - stop tracking icon
     * 2 - saving icon
     */
    private fun changeTrackerButton(status: Int, animate: Boolean) {
        when (status) {
            0 -> if (animate) {
                fabTrack!!.setImageDrawable(playToPause)
                playToPause!!.start()
            } else
                fabTrack!!.setImageDrawable(pauseToPlay)
            1 -> if (animate) {
                fabTrack!!.setImageDrawable(pauseToPlay)
                pauseToPlay!!.start()
            } else
                fabTrack!!.setImageDrawable(playToPause)
        }
    }

    /**
     * Enables or disables collecting service
     *
     * @param enable ensures intended action
     */
    private fun toggleCollecting(activity: Activity, enable: Boolean) {
        if (TrackerService.isRunning == enable)
            return

        val requiredPermissions = Assist.checkTrackingPermissions(activity)

        if (requiredPermissions == null) {
            if (!TrackerService.isRunning) {
                if (!Assist.isGNSSEnabled(activity)) {
                    SnackMaker(activity).showSnackbar(R.string.error_gnss_not_enabled, R.string.enable, View.OnClickListener{ _ ->
                        val gpsOptionsIntent = Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        startActivity(gpsOptionsIntent)
                    })
                } else if (!Assist.canTrack(activity)) {
                    SnackMaker(activity).showSnackbar(R.string.error_nothing_to_track)
                } else {
                    Preferences.getPref(activity).edit().putBoolean(Preferences.PREF_STOP_TILL_RECHARGE, false).apply()
                    val trackerService = Intent(activity, TrackerService::class.java)
                    trackerService.putExtra("backTrack", false)
                    activity.startService(trackerService)
                }
            } else {
                activity.stopService(Intent(activity, TrackerService::class.java))
            }

        } else if (Build.VERSION.SDK_INT >= 23) {
            activity.requestPermissions(requiredPermissions, 0)
        }
    }


    private fun updateUploadButton() {
        if (fabUp == null || Network.cloudStatus == CloudStatus.UNKNOWN) {
            Log.e("SignalsTrackerFragment", "fab " + (if (fabUp == null) " is null " else " is fine ") + " done " + if (Network.cloudStatus == CloudStatus.UNKNOWN) " is null " else " is fine")
            FirebaseCrash.report(Exception("fab " + (if (fabUp == null) " is null " else " is fine ") + " done " + if (Network.cloudStatus == CloudStatus.UNKNOWN) " is null " else " is fine"))
            return
        }

        when (Network.cloudStatus) {
            CloudStatus.NO_SYNC_REQUIRED -> {
                fabUp!!.hide()
                fabUp!!.setOnClickListener(null)
            }
            CloudStatus.SYNC_AVAILABLE -> {
                fabUp!!.setImageResource(R.drawable.ic_cloud_upload_24dp)
                progressBar!!.visibility = View.GONE
                fabUp!!.setOnClickListener { _ ->
                    if (Signin.isSignedIn) {
                        val context = context
                        val failure = UploadJobService.requestUpload(context!!, UploadJobService.UploadScheduleSource.USER)
                        FirebaseAnalytics.getInstance(context).logEvent(FirebaseAssist.MANUAL_UPLOAD_EVENT, Bundle())
                        if (failure.hasFailed())
                            SnackMaker(activity!!).showSnackbar(failure.value!!)
                        else {
                            updateUploadProgress(0)
                            updateUploadButton()
                        }
                    } else {
                        SnackMaker(activity!!).showSnackbar(R.string.sign_in_required)
                    }
                }
                fabUp!!.show()
            }
            CloudStatus.SYNC_SCHEDULED -> {
                fabUp!!.setImageResource(R.drawable.ic_cloud_queue_black_24dp)
                fabUp!!.setOnClickListener { _ ->
                    val context = context
                    val failure = UploadJobService.requestUpload(context!!, UploadJobService.UploadScheduleSource.USER)
                    FirebaseAnalytics.getInstance(context).logEvent(FirebaseAssist.MANUAL_UPLOAD_EVENT, Bundle())
                    if (failure.hasFailed())
                        SnackMaker(activity!!).showSnackbar(failure.value!!)
                    else {
                        updateUploadButton()
                    }
                }
                fabUp!!.show()
            }
            CloudStatus.SYNC_IN_PROGRESS -> {
                fabUp!!.setImageResource(R.drawable.ic_sync_black_24dp)
                fabUp!!.setOnClickListener(null)
                fabUp!!.show()
            }
            CloudStatus.ERROR -> {
                fabUp!!.setImageResource(R.drawable.ic_cloud_off_24dp)
                fabUp!!.setOnClickListener(null)
                fabUp!!.show()
            }
            CloudStatus.UNKNOWN -> {
            }
        }//do nothing
    }

    private fun updateUploadProgress(percentage: Int) {
        if (activity == null)
            return

        val context = activity!!.applicationContext
        val progressBar = progressBar!!
        progressBar.visibility = View.VISIBLE
        fabUp!!.elevation = 0f
        if (handler == null)
            handler = Handler()

        when (percentage) {
            0 -> {
                progressBar.isIndeterminate = true
                updateUploadButton()
            }
            -1 -> {
                progressBar.animate().alpha(0f).setDuration(400).start()
                fabUp!!.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.error))
                fabUp!!.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_primary))
                fabUp!!.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_close_black_24dp))
                handler!!.postDelayed({
                    fabUp!!.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_primary))
                    fabUp!!.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.color_accent))
                    updateUploadButton()
                    resetFabElevation(fabUp, resources)
                }, 3000)
            }
            else -> {
                progressBar.isIndeterminate = false
                val animation = ObjectAnimator.ofInt(progressBar, "progress", percentage)
                animation.duration = 400
                if (percentage == 100) {
                    handler!!.postDelayed(handler@ {
                        if (fabUp == null)
                            return@handler
                        fabUp!!.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.color_accent))
                        fabUp!!.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_primary))
                        fabUp!!.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_check_black_24dp))

                        progressBar.animate().alpha(0f).setDuration(400).start()

                        handler!!.postDelayed({
                            progressBar.visibility = View.GONE
                            if (DataStore.sizeOfData(context) > 0) {
                                fabUp!!.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_primary))
                                fabUp!!.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.color_accent))
                                resetFabElevation(fabUp, resources)
                                updateUploadButton()
                            } else {
                                fabUp!!.hide()
                                handler!!.postDelayed({
                                    fabUp!!.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_primary))
                                    fabUp!!.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.color_accent))
                                    resetFabElevation(fabUp, resources)
                                }, 300)
                            }
                        }, 1000)
                    }, 600)
                }
                animation.start()
            }
        }
    }

    private fun resetFabElevation(fab: FloatingActionButton?, resources: Resources) {
        fab!!.elevation = 6 * resources.displayMetrics.density
    }

    override fun onEnter(activity: FragmentActivity, fabOne: FloatingActionButton, fabTwo: FloatingActionButton): Failure<String> {
        fabTrack = fabOne
        fabUp = fabTwo
        progressBar = (fabTwo.parent as ViewGroup).findViewById(R.id.progressBar)

        if (UploadJobService.isUploading || UploadJobService.getUploadScheduled(activity) == UploadJobService.UploadScheduleSource.USER) {
            updateUploadProgress(0)
        } else {
            progressBar!!.progress = 0
        }

        updateUploadButton()

        fabTrack!!.show()

        if (pauseToPlay == null) {
            pauseToPlay = ContextCompat.getDrawable(activity, R.drawable.avd_play_to_pause) as AnimatedVectorDrawable?
            playToPause = ContextCompat.getDrawable(activity, R.drawable.avd_pause_to_play) as AnimatedVectorDrawable?
        }

        changeTrackerButton(if (TrackerService.isRunning) 1 else 0, false)
        fabTrack!!.setOnClickListener { _ ->
            if (TrackerService.isRunning && TrackerService.isBackgroundActivated) {
                val lockedForMinutes = TrackerService.setAutoLock()
                SnackMaker(activity).showSnackbar(activity.resources.getQuantityString(R.plurals.notification_auto_tracking_lock, lockedForMinutes, lockedForMinutes))
            } else
                toggleCollecting(activity, !TrackerService.isRunning)
        }
        DataStore.setOnDataChanged(ICallback { activity.runOnUiThread { setCollected(activity, DataStore.sizeOfData(activity), DataStore.collectionCount(activity)) } })
        DataStore.setOnUploadProgress({ progress -> activity.runOnUiThread { updateUploadProgress(progress) } })
        TrackerService.onNewDataFound = ICallback { activity.runOnUiThread { this.updateData() } }
        TrackerService.onServiceStateChange = ICallback { activity.runOnUiThread { changeTrackerButton(if (TrackerService.isRunning) 1 else 0, true) } }

        //TrackerService.rawDataEcho = new RawData(200).setActivity(1).addCell("Some Operator", null).setLocation(new Location("test")).setWifi(new android.net.wifi.ScanResult[0], 10);

        if (layoutWifi != null)
            updateData(activity)

        //todo move this check to upload scheduling
        //if (Assist.isEmulator())
        //	fabUp.hide();
        return Failure()
    }

    override fun onLeave(activity: FragmentActivity) {
        if (handler != null)
            handler!!.removeCallbacksAndMessages(null)

        DataStore.setOnDataChanged(null)
        DataStore.setOnUploadProgress(null)
        TrackerService.onNewDataFound = null
        TrackerService.onServiceStateChange = null
        progressBar!!.visibility = View.GONE
        progressBar!!.alpha = 1f
        resetFabElevation(fabUp, activity.resources)
        fabUp!!.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.text_primary))
        fabUp!!.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.color_accent))
        fabUp = null
        fabTrack = null
    }

    override fun onPermissionResponse(requestCode: Int, success: Boolean) {

    }

    override fun onHomeAction() {}

    private fun updateData(context: Context) {
        val res = context.resources
        val d = TrackerService.rawDataEcho
        setCollected(context, DataStore.sizeOfData(context), DataStore.collectionCount(context))

        if (DataStore.sizeOfData(context) >= Constants.MIN_USER_UPLOAD_FILE_SIZE && Network.cloudStatus == CloudStatus.NO_SYNC_REQUIRED) {
            Network.cloudStatus = CloudStatus.SYNC_AVAILABLE
            updateUploadButton()
        }

        textTime!!.visibility = View.VISIBLE
        textTime!!.text = res.getString(R.string.main_last_update, DateFormat.format("HH:mm:ss", d.time))

        if (d.accuracy != null) {
            textAccuracy!!.visibility = View.VISIBLE
            textAccuracy!!.text = res.getString(R.string.main_accuracy, d.accuracy!!.toInt())
        } else
            textAccuracy!!.visibility = View.GONE

        if (d.latitude != null && d.longitude != null) {
            textPosition!!.visibility = View.VISIBLE
            textPosition!!.text = res.getString(R.string.main_position,
                    Assist.coordsToString(d.latitude!!),
                    Assist.coordsToString(d.longitude!!),
                    d.altitude!!.toInt())
        } else
            textPosition!!.visibility = View.GONE

        when {
            d.wifi != null -> {
                textWifiCount!!.text = res.getString(R.string.main_wifi_count, d.wifi!!.size)
                textWifiCollection!!.text = res.getString(R.string.main_wifi_updated, TrackerService.distanceToWifi)
                lastWifiTime = d.time
                layoutWifi!!.visibility = View.VISIBLE
            }
            lastWifiTime - d.time < 10000 -> textWifiCollection!!.text = res.getString(R.string.main_wifi_updated, TrackerService.distanceToWifi)
            else -> layoutWifi!!.visibility = View.GONE
        }

        if (d.cellCount != null) {
            val active = d.registeredCells
            if (active != null && active.isNotEmpty()) {
                textCurrentCell!!.visibility = View.VISIBLE
                textCurrentCell!!.text = res.getString(R.string.main_cell_current, active[0].getType(), active[0].dbm, active[0].asu)
            } else
                textCurrentCell!!.visibility = View.GONE
            textCellCount!!.text = res.getString(R.string.main_cell_count, d.cellCount)
            layoutCell!!.visibility = View.VISIBLE
        } else {
            layoutCell!!.visibility = View.GONE
        }


        /*if (d.noise > 0) {
            textNoise.setText(String.format(res.getString(R.string.main_noise), (int) d.noise, (int) Assist.amplitudeToDbm(d.noise)));
        } else if (Preferences.getPref(context).getBoolean(Preferences.PREF_TRACKING_NOISE_ENABLED, false)) {
            textNoise.setText(res.getString(R.string.main_noise_not_collected));
        } else
            textNoise.setText(res.getString(R.string.main_noise_disabled));*/

        if (d.activity != null) {
            textActivity!!.text = String.format(res.getString(R.string.main_activity), ActivityInfo.getResolvedActivityName(context, d.activity!!))
            textActivity!!.visibility = View.VISIBLE
        } else {
            textActivity!!.visibility = View.GONE
        }
    }

    private fun updateData() {
        val c = context
        if (c != null)
            updateData(c)
    }

}
