package com.adsamcik.tracker.common.fragment

import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.fragment.app.Fragment
import com.adsamcik.tracker.common.Reporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlin.coroutines.CoroutineContext

abstract class CoreFragment : Fragment(), CoroutineScope {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	@CallSuper
	override fun onCreate(savedInstanceState: Bundle?) {
		Reporter.initialize(requireContext())
		super.onCreate(savedInstanceState)
	}

	override fun onDestroy() {
		super.onDestroy()
		coroutineContext.cancelChildren()
	}
}
