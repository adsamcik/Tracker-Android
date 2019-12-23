package com.adsamcik.tracker.shared.utils.fragment

import androidx.annotation.CallSuper
import com.adsamcik.tracker.shared.base.fragment.CoreFragment
import com.adsamcik.tracker.shared.utils.style.StyleController
import com.adsamcik.tracker.shared.utils.style.StyleManager

abstract class CoreUIFragment : CoreFragment() {
	protected val styleController: StyleController = StyleManager.createController()

	@CallSuper
	override fun onDestroy() {
		StyleManager.recycleController(styleController)
		super.onDestroy()
	}

	@CallSuper
	override fun onPause() {
		styleController.isSuspended = true
		super.onPause()
	}

	@CallSuper
	override fun onResume() {
		styleController.isSuspended = false
		super.onResume()
	}
}
