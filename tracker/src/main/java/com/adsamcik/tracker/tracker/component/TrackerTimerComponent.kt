package com.adsamcik.tracker.tracker.component

import android.content.Context
import androidx.annotation.WorkerThread

internal interface TrackerTimerComponent {
	val requiredPermissions: Collection<String>
	fun onEnable(context: Context, @WorkerThread receiver: TrackerTimerReceiver)
	fun onDisable(context: Context)
}

internal class NoTimer : TrackerTimerComponent {
	override val requiredPermissions: Collection<String> get() = emptyList()

	override fun onEnable(context: Context, receiver: TrackerTimerReceiver) {
		throw TrackerTimerNotInitializedException()
	}

	override fun onDisable(context: Context) {
		throw TrackerTimerNotInitializedException()
	}
}

internal class TrackerTimerNotInitializedException : Exception()
