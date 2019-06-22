package com.adsamcik.signalcollector.app.activity

import android.os.Bundle
import com.adsamcik.signalcollector.common.activity.CoreActivity
import com.adsamcik.signalcollector.common.misc.extension.startActivity


/**
 * LaunchActivity is activity that should always be called first when user should see the layout
 * Not only does it allow for easy switching of appropriate Activities, but it also shows SplashScreen and initializes basic services
 */
class LaunchActivity : CoreActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		startActivity<MainActivity> { }

		overridePendingTransition(0, 0)
		finish()
	}
}
