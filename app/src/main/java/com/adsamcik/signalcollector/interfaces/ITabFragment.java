package com.adsamcik.signalcollector.interfaces;

import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.FragmentActivity;

import com.adsamcik.signalcollector.utility.Failure;

public interface ITabFragment{

	/**
	 * Called when entering the tab
	 * @return if tab successfully loaded
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	Failure<String> onEnter(final FragmentActivity activity, final FloatingActionButton fabOne, final FloatingActionButton fabTwo);

	/**
	 * Called when leaving tab
	 */
	void onLeave();

	/**
	 * Called when permissions result comes back
	 * @param success success
	 */
	void onPermissionResponse(int requestCode, boolean success);

	/**
	 * Home action that is performed
	 */
	void onHomeAction();
}
