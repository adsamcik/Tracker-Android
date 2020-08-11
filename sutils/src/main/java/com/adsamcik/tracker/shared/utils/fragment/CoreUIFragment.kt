package com.adsamcik.tracker.shared.utils.fragment

import androidx.annotation.CallSuper
import com.adsamcik.tracker.shared.base.fragment.CoreFragment
import com.adsamcik.tracker.shared.utils.style.StyleController
import com.adsamcik.tracker.shared.utils.style.StyleManager

/**
 * Abstract fragment class containing UI specific extensions to [CoreFragment].
 * This class should be used for every UI fragment as it provides convenient
 * lifecycle management of styleController.
 */
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
