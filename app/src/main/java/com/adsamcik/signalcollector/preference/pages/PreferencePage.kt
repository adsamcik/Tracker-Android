package com.adsamcik.signalcollector.preference.pages

import androidx.preference.PreferenceFragmentCompat

interface PreferencePage {
	fun onEnter(caller: PreferenceFragmentCompat)
	fun onExit(caller: PreferenceFragmentCompat)
}