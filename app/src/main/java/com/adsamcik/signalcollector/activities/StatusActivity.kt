package com.adsamcik.signalcollector.activities

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import androidx.work.WorkManager
import com.adsamcik.signalcollector.extensions.dpAsPx
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.jobs.UploadWorker
import com.adsamcik.signalcollector.utility.TrackingLocker

/**
 * Debug Activity used for displaying states of some parts of the app
 */
class StatusActivity : DetailActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = createScrollableContentParent(true, androidx.constraintlayout.widget.ConstraintLayout::class.java)
        val workManager = WorkManager.getInstance()
        val uploadWorkStates = workManager.getStatusesByTag(UploadWorker.UPLOAD_TAG).value
        val scheduleUploadWorkStates = workManager.getStatusesByTag(UploadWorker.UPLOAD_TAG).value
        val waitForRechargeStates = workManager.getStatusesByTag(TrackingLocker.JOB_DISABLE_TILL_RECHARGE_TAG).value

        val hasUploadPending = uploadWorkStates != null && uploadWorkStates.size > 0
        val hasUploadScheduled = scheduleUploadWorkStates != null && scheduleUploadWorkStates.size > 0
        val isWaitingForRecharge = waitForRechargeStates != null && waitForRechargeStates.size > 0

        var lastId = createPair("Is upload pending", hasUploadPending.toString(), layout, null)
        lastId = createPair("Is upload scheduled", hasUploadScheduled.toString(), layout, lastId)
        lastId = createPair("Upload schedule source", UploadWorker.getUploadScheduled(this).toString(), layout, lastId)
        lastId = createPair("Is uploading", UploadWorker.isUploading.toString(), layout, lastId)
        lastId = createPair("Is time locked", TrackingLocker.isTimeLocked.toString(), layout, lastId)
        lastId = createPair("Is locked until recharge", TrackingLocker.isChargeLocked.toString(), layout, lastId)
        lastId = createPair("Has active wait for recharge job", isWaitingForRecharge.toString(), layout, lastId)
        createPair("Current DataFile", DataStore.currentDataFile?.file?.name.toString(), layout, lastId)
    }

    fun createPair(titleString: String, valueString: String, parent: ViewGroup, aboveId: Int?): Int {
        val title = TextView(this)
        val titleParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

        if (aboveId != null)
            titleParams.topToBottom = aboveId

        title.textSize = 16f
        title.layoutParams = titleParams
        title.text = titleString
        title.id = View.generateViewId()

        parent.addView(title)

        val value = TextView(this)
        val valueParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

        valueParams.leftToRight = title.id
        valueParams.topToTop = title.id
        valueParams.marginStart = 16.dpAsPx

        value.layoutParams = valueParams
        value.text = valueString
        value.textSize = 16f

        when (valueString) {
            "true" -> value.setTextColor(Color.GREEN)
            "false" -> value.setTextColor(Color.RED)
            else -> value.setTextColor(Color.BLUE)
        }

        parent.addView(value)


        return title.id
    }
}
