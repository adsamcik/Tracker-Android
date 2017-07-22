package com.adsamcik.signalcollector.fragments;

import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;

import com.adsamcik.signalcollector.interfaces.ITabFragment;
import com.adsamcik.signalcollector.utility.Failure;

public class FragmentActivities extends Fragment implements ITabFragment {
	@NonNull
	@Override
	public Failure<String> onEnter(@NonNull FragmentActivity activity, @NonNull FloatingActionButton fabOne, @NonNull FloatingActionButton fabTwo) {
		return new Failure<>();
	}

	@Override
	public void onLeave(@NonNull FragmentActivity activity) {

	}

	@Override
	public void onPermissionResponse(int requestCode, boolean success) {

	}

	@Override
	public void onHomeAction() {

	}
}
