package com.adsamcik.tracker.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class BootReceiver : BroadcastReceiver() {

	@EntryPoint
	@InstallIn(SingletonComponent::class)
	interface BootReceiverEntryPoint {
		fun bootTrackingRecoveryScheduler(): BootTrackingRecoveryScheduler
	}

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
		val entryPoint = EntryPointAccessors.fromApplication(
			context.applicationContext,
			BootReceiverEntryPoint::class.java,
		)
		// BroadcastReceiver execution stays bounded. The unique worker owns startup recovery,
		// lock hydration, and accepted-or-terminal Activity control reconciliation.
		entryPoint.bootTrackingRecoveryScheduler().enqueue()
	}
}
