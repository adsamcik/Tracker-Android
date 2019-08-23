package com.adsamcik.tracker.common.activity

import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import com.adsamcik.tracker.common.debug.Reporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlin.coroutines.CoroutineContext

abstract class CoreActivity : AppCompatActivity(), CoroutineScope {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	@CallSuper
	override fun onCreate(savedInstanceState: Bundle?) {
		Reporter.initialize(this)
		super.onCreate(savedInstanceState)
	}

	override fun onDestroy() {
		super.onDestroy()
		coroutineContext.cancelChildren()
	}
}
