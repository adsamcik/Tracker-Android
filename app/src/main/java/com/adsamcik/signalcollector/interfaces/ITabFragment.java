package com.adsamcik.signalcollector.interfaces;

import android.app.Activity;
import android.support.design.widget.FloatingActionButton;

public interface ITabFragment {

	/**
	 * Called when entering the tab
	 * @return if tab successfully loaded
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	boolean onEnter(Activity activity, FloatingActionButton fabOne, FloatingActionButton fabTwo);

	/**
	 * Called when leaving tab
	 */
	void onLeave();
}
