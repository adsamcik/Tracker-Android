package com.adsamcik.tracker.shared.utils.activity

import android.content.Context
import android.os.Bundle
import androidx.annotation.CallSuper
import com.adsamcik.tracker.shared.base.activity.CoreActivity
import com.adsamcik.tracker.shared.utils.language.LocaleContextWrapper
import com.adsamcik.tracker.shared.utils.language.LocaleManager

/**
 * Abstract activity class implementing language support on top of [CoreActivity].
 * Note: StyleController support removed - migrate to Compose with ThemeRepository for theming.
 */
abstract class CoreUIActivity : CoreActivity() {
	private var language = ""

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		// Theme initialization handled by ThemeRepository in Application.kt
	}

	@CallSuper
	override fun onDestroy() {
		super.onDestroy()
	}

	@CallSuper
	override fun onPause() {
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
