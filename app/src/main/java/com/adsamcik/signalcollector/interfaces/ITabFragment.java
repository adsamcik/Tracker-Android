package com.adsamcik.signalcollector.interfaces;

import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.FragmentActivity;

import com.adsamcik.signalcollector.utility.Failure;

public interface ITabFragment{

	/**
	 * Called when entering the tab
	 * @return if tab successfully loaded
	 */
	Failure<String> onEnter(@NonNull final FragmentActivity activity, @NonNull final FloatingActionButton fabOne, @NonNull final FloatingActionButton fabTwo);

	/**
	 * Called when leaving tab
	 */
	void onLeave(@NonNull final FragmentActivity activity);

	/**
	 * Called when permissions result comes back
	 * @param success success
	 */
	void onPermissionResponse(final int requestCode, final boolean success);

	/**
	 * Home action that is performed
	 */
	void onHomeAction();
}
