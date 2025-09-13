package com.adsamcik.tracker.app.activity

import android.os.Bundle
import androidx.activity.ComponentActivity

@Deprecated("Legacy activity removed; use MainActivityCompose")
class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		// Immediately finish; launcher is MainActivityCompose
		finish()
	}
}

