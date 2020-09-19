package com.adsamcik.tracker.shared.base.service

import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * A Service that is [androidx.lifecycle.LifecycleOwner] and has a [CoroutineScope].
 */
abstract class CoreService : LifecycleService(), CoroutineScope {
	private val job = SupervisorJob()
	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Default + job

}
