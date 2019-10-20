package com.adsamcik.tracker.common.activity

import android.content.Context
import androidx.annotation.CallSuper
import com.adsamcik.tracker.common.language.LocaleContextWrapper
import com.adsamcik.tracker.common.language.LocaleManager
import com.adsamcik.tracker.common.style.StyleController
import com.adsamcik.tracker.common.style.StyleManager

abstract class CoreUIActivity : CoreActivity() {
	protected val styleController: StyleController = StyleManager.createController()

	private val permissionRequestList = mutableListOf<Pair<Int, PermissionRequest>>()
	private var lastPermissionRequestId = 1000

	private var language = ""

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

	private fun recreateIfLanguageChanged() {
		val language: String = LocaleManager.getLocale(this)
		if (language != this.language) {
			this.language = language
			recreate()
		}
	}

	@CallSuper
	override fun onResume() {
		styleController.isSuspended = false
		initializeColors()
		super.onResume()
		recreateIfLanguageChanged()
	}

	@CallSuper
	override fun attachBaseContext(newBase: Context) {
		super.attachBaseContext(LocaleContextWrapper.wrap(newBase))
		if (language.isEmpty()) {
			language = LocaleManager.getLocale(this)
		}
	}

	private fun initializeColors() {
		StyleManager.initializeFromPreferences(this)
	}
}
