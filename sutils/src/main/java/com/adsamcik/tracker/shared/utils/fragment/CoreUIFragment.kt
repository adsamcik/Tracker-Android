package com.adsamcik.tracker.shared.utils.fragment

import androidx.annotation.CallSuper
import com.adsamcik.tracker.shared.base.fragment.CoreFragment
import com.adsamcik.tracker.shared.utils.style.StyleController
import com.adsamcik.tracker.shared.utils.style.StyleManager

/**
 * Abstract fragment class containing UI specific extensions to [CoreFragment].
 * This class should be used for every UI fragment. 
 * 
 * NOTE: StyleController is deprecated - migrate to Compose with Material 3 theming instead.
 */
abstract class CoreUIFragment : CoreFragment() {
	
	/**
	 * Legacy StyleController for backward compatibility.
	 * @deprecated Use Compose with Material 3 theming instead of StyleController.
	 */
	@Deprecated("Use Compose with Material 3 theming instead of StyleController")
	protected val styleController: StyleController = StyleManager.createController()

	@CallSuper
	override fun onDestroy() {
		@Suppress("DEPRECATION")
		StyleManager.recycleController(styleController)
		super.onDestroy()
	}

	@CallSuper
	override fun onPause() {
		@Suppress("DEPRECATION")
		styleController.isSuspended = true
		super.onPause()
	}

	@CallSuper
	override fun onResume() {
		@Suppress("DEPRECATION")
		styleController.isSuspended = false
		super.onResume()
	}
}
