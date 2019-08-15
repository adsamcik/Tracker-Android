package com.adsamcik.signalcollector.preference.pages

import android.content.Context
import androidx.preference.PreferenceFragmentCompat

interface PreferencePage {
	fun onEnter(caller: PreferenceFragmentCompat)
	fun onExit(caller: PreferenceFragmentCompat)
	fun onRequestPermissionsResult(context: Context, code: Int, result: Collection<Pair<String, Int>>) {}
}
