package com.adsamcik.tracker.shared.base.activity

import androidx.appcompat.app.AppCompatActivity
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlin.coroutines.CoroutineContext

/**
 * Abstract Activity providing CoroutineScope.
 */
abstract class CoreActivity(
	private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
) : AppCompatActivity(), CoroutineScope {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = dispatchers.main + job

	override fun onDestroy() {
		coroutineContext.cancelChildren()
		super.onDestroy()
	}
}
