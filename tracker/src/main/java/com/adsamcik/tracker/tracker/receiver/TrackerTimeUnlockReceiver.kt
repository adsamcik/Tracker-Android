package com.adsamcik.tracker.tracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.tracker.controller.LockManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives time unlock broadcasts
 */
@AndroidEntryPoint
class TrackerTimeUnlockReceiver : BroadcastReceiver() {
	
	@Inject
	lateinit var lockManager: LockManager
	
	override fun onReceive(context: Context, intent: Intent) {
		lockManager.unlockTimeLock(context)
	}

}

