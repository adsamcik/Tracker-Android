package com.adsamcik.signalcollector.common.introduction

import androidx.fragment.app.FragmentActivity
import com.takusemba.spotlight.Target

abstract class Introduction {
	abstract val key: String
	val preference: String get() = "$prefix:$key"

	private val onDoneListenerList = mutableListOf<() -> Unit>()

	fun addOnDoneListener(listener: () -> Unit) {
		onDoneListenerList.add(listener)
	}

	internal fun onDone() {
		onDoneListenerList.forEach { it.invoke() }
		onDoneListenerList.clear()
	}

	abstract fun getTargets(activity: FragmentActivity): Collection<Target>

	companion object {
		const val prefix = "introduction"
	}
}