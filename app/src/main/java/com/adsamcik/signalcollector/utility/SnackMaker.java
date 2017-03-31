package com.adsamcik.signalcollector.utility;

import android.app.Activity;
import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.StringRes;
import android.support.design.widget.Snackbar;
import android.view.View;

import com.adsamcik.signalcollector.R;

public class SnackMaker {
	private final View view;

	public SnackMaker(View view) {
		this.view = view;
	}

	public SnackMaker(Activity activity) {
		this.view = activity.findViewById(R.id.fabCoordinator);
	}

	public void showSnackbar(@NonNull String message) {
		Snackbar.make(view, message, Snackbar.LENGTH_LONG).show();
	}

	public void showSnackbar(@StringRes int message) {
		Snackbar.make(view, message, Snackbar.LENGTH_LONG).show();
	}

	public void showSnackbar(@StringRes int message, @StringRes int action, @NonNull View.OnClickListener onClickListener) {
		Snackbar.make(view, message, Snackbar.LENGTH_LONG).setAction(action, onClickListener).show();
	}
}
