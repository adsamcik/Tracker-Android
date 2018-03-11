package com.adsamcik.signalcollector.interfaces;

import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.FragmentActivity;

public interface ITabFragment {

	/**
	 * Called when entering the tab
	 *
	 */
	@NonNull
	void onEnter(@NonNull final FragmentActivity activity, @NonNull final FloatingActionButton fabOne, @NonNull final FloatingActionButton fabTwo);

	/**
	 * Called when leaving tab
	 */
	void onLeave(@NonNull final FragmentActivity activity);

	/**
	 * Called when permissions result comes back
	 *
	 * @param success success
	 */
	void onPermissionResponse(final int requestCode, final boolean success);

	/**
	 * Home action that is performed
	 */
	void onHomeAction();
}
