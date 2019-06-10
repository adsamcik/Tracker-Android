package com.adsamcik.signalcollector.game.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.reflect.KClass

class OnSessionFinishedReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, p1: Intent?) {
		BroadcastManager.onBroadcast(context, this::class as KClass<out BroadcastReceiver>)
	}
}