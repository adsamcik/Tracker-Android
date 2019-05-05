package com.adsamcik.signalcollector.app

import android.content.Context
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitcompat.SplitCompatApplication

class Application : SplitCompatApplication() {
	override fun attachBaseContext(base: Context) {
		super.attachBaseContext(base)
		// Emulates installation of future on demand modules using SplitCompat.
		SplitCompat.install(this)
	}

}
