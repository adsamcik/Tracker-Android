package com.adsamcik.tracker.tracker.service

import android.os.Handler
import android.os.Looper
import com.adsamcik.tracker.tracker.resilience.TrackingStopCommand
import java.util.IdentityHashMap

/** Process-local delivery to the one TrackerService owner; never starts an absent service. */
object TrackerRuntimeStopDispatcher {
	private val lock = Any()
	private val mainHandler = Handler(Looper.getMainLooper())
	private var owner: Any? = null
	private var handler: ((TrackingStopCommand) -> Boolean)? = null
	private val teardownFallbacks =
		IdentityHashMap<Any, MutableList<PendingFallback>>()
	private val teardownOrder = mutableListOf<Any>()

	fun register(owner: Any, handler: (TrackingStopCommand) -> Boolean) {
		synchronized(lock) {
			require(!teardownFallbacks.containsKey(owner)) {
				"A runtime-stop owner cannot register while its teardown is pending"
			}
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

	/**
	 * Atomically transfers a live service into teardown ownership. Stops arriving in this interval
	 * remain delivered, but their inactive fallback is deferred until provider teardown is proven.
	 */
	fun beginTeardown(owner: Any): Boolean = synchronized(lock) {
		if (teardownFallbacks.containsKey(owner)) return@synchronized true
		if (this.owner === owner) {
			this.owner = null
			handler = null
		}
		teardownFallbacks[owner] = mutableListOf()
		teardownOrder += owner
		true
	}

	/** Releases each stop deferred for [owner] exactly once, after runtime teardown completes. */
	fun completeTeardown(owner: Any): Boolean {
		val pending = synchronized(lock) {
			val released = teardownFallbacks.remove(owner) ?: return false
			teardownOrder.removeAll { candidate -> candidate === owner }
			released.toList()
		}
		pending.forEach { fallback -> fallback.onUndelivered(fallback.command) }
		return true
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
			val targetOwner = owner
			val targetHandler = handler
			if (targetOwner != null && targetHandler != null) {
				DispatchTarget.Live(Registration(targetOwner, targetHandler))
			} else {
				val teardownOwner = teardownOrder.lastOrNull() ?: return false
				DispatchTarget.Teardown(teardownOwner)
			}
		}
		if (target is DispatchTarget.Teardown) {
			return deferIfTearingDown(target.owner, command, onUndelivered)
		}
		val liveTarget = (target as DispatchTarget.Live).registration
		if (Looper.myLooper() == Looper.getMainLooper()) {
			return invokeOrDefer(liveTarget, command, onUndelivered)
		}
		return postToMain(Runnable {
			if (!invokeOrDefer(liveTarget, command, onUndelivered)) onUndelivered(command)
		})
	}

	private fun invokeOrDefer(
		target: Registration,
		command: TrackingStopCommand,
		onUndelivered: (TrackingStopCommand) -> Unit,
	): Boolean {
		val currentHandler = synchronized(lock) {
			when {
				owner === target.owner && handler === target.handler -> target.handler
				teardownFallbacks.containsKey(target.owner) -> {
					teardownFallbacks.getValue(target.owner) += PendingFallback(command, onUndelivered)
					null
				}
				else -> return false
			}
		}
		return currentHandler?.invoke(command) ?: true
	}

	private fun deferIfTearingDown(
		owner: Any,
		command: TrackingStopCommand,
		onUndelivered: (TrackingStopCommand) -> Unit,
	): Boolean = synchronized(lock) {
		val pending = teardownFallbacks[owner] ?: return@synchronized false
		pending += PendingFallback(command, onUndelivered)
		true
	}

	private sealed interface DispatchTarget {
		data class Live(val registration: Registration) : DispatchTarget
		data class Teardown(val owner: Any) : DispatchTarget
	}

	private data class Registration(
		val owner: Any,
		val handler: (TrackingStopCommand) -> Boolean,
	)

	private data class PendingFallback(
		val command: TrackingStopCommand,
		val onUndelivered: (TrackingStopCommand) -> Unit,
	)
}
