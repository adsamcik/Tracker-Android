package com.adsamcik.tracker.shared.utils.activity

import android.content.Context
import android.os.Bundle
import androidx.annotation.CallSuper
import com.adsamcik.tracker.common.activity.CoreActivity
import com.adsamcik.tracker.shared.utils.language.LocaleContextWrapper
import com.adsamcik.tracker.shared.utils.language.LocaleManager
import com.adsamcik.tracker.shared.utils.style.StyleController
import com.adsamcik.tracker.shared.utils.style.StyleManager

abstract class CoreUIActivity : CoreActivity() {
	protected val styleController: StyleController = StyleManager.createController()

	private var language = ""

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		StyleManager.initializeFromPreferences(this)
	}

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
		StyleManager.onResume(this)
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
}
