package com.adsamcik.tracker.shared.base.activity

import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlin.coroutines.CoroutineContext

/**
 * Abstract Activity providing CoroutineScope.
 */
abstract class CoreActivity : AppCompatActivity(), CoroutineScope {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	override fun onDestroy() {
		coroutineContext.cancelChildren()
		super.onDestroy()
	}
}
