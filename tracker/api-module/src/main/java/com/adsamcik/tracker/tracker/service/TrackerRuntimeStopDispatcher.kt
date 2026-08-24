package com.adsamcik.tracker.tracker.service

import android.os.Handler
import android.os.Looper
import com.adsamcik.tracker.tracker.resilience.TrackingStopCommand

/** Process-local delivery to the one TrackerService owner; never starts an absent service. */
object TrackerRuntimeStopDispatcher {
	private val lock = Any()
	private val mainHandler = Handler(Looper.getMainLooper())
	private var owner: Any? = null
	private var handler: ((TrackingStopCommand) -> Boolean)? = null

	fun register(owner: Any, handler: (TrackingStopCommand) -> Boolean) {
		synchronized(lock) {
			this.owner = owner
			this.handler = handler
		}
	}

	fun unregister(owner: Any) {
		synchronized(lock) {
			if (this.owner === owner) {
				this.owner = null
				handler = null
			}
		}
	}

	fun dispatch(
		command: TrackingStopCommand,
		onUndelivered: (TrackingStopCommand) -> Unit,
	): Boolean = dispatch(command, onUndelivered, mainHandler::post)

	internal fun dispatch(
		command: TrackingStopCommand,
		onUndelivered: (TrackingStopCommand) -> Unit,
		postToMain: (Runnable) -> Boolean,
	): Boolean {
		val target = synchronized(lock) {
			val targetOwner = owner ?: return false
			val targetHandler = handler ?: return false
			Registration(targetOwner, targetHandler)
		}
		if (Looper.myLooper() == Looper.getMainLooper()) {
			return invokeIfCurrent(target, command)
		}
		return postToMain(Runnable {
			if (!invokeIfCurrent(target, command)) onUndelivered(command)
		})
	}

	private fun invokeIfCurrent(
		target: Registration,
		command: TrackingStopCommand,
	): Boolean {
		val current = synchronized(lock) {
			owner === target.owner && handler === target.handler
		}
		return current && target.handler(command)
	}

	private data class Registration(
		val owner: Any,
		val handler: (TrackingStopCommand) -> Boolean,
	)
}
