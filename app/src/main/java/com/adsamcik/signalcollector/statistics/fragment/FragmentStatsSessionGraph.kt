package com.adsamcik.signalcollector.statistics.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.DialogFragment
import com.adsamcik.signalcollector.misc.extension.getNonNullContext

class FragmentStatsSessionGraph : DialogFragment() {


	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		return FrameLayout(getNonNullContext()).apply {
			container?.addView(this)
		}
	}
}