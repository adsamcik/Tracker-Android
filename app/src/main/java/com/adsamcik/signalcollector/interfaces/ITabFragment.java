package com.adsamcik.signalcollector.interfaces;

import android.app.Activity;
import android.support.design.widget.FloatingActionButton;

import com.adsamcik.signalcollector.classes.Success;

public interface ITabFragment {

	/**
	 * Called when entering the tab
	 * @return if tab successfully loaded
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	Success onEnter(final Activity activity, final FloatingActionButton fabOne, final FloatingActionButton fabTwo);

	/**
	 * Called when leaving tab
	 */
	void onLeave();
}
