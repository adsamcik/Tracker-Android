package com.adsamcik.signalcollector.interfaces;

import android.app.Activity;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;

import com.adsamcik.signalcollector.classes.Success;

public interface ITabFragment{

	/**
	 * Called when entering the tab
	 * @return if tab successfully loaded
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	Success onEnter(final FragmentActivity activity, final FloatingActionButton fabOne, final FloatingActionButton fabTwo);

	/**
	 * Called when leaving tab
	 */
	void onLeave();

	ITabFragment newInstance();
}
