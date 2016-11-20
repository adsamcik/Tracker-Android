package com.adsamcik.signalcollector.utility;

import android.support.design.widget.Snackbar;
import android.view.View;

public class SnackMaker {
	private final View view;
	public SnackMaker(View view) {
		this.view = view;
	}

	public void showSnackbar(String message) {
		Snackbar.make(view, message, Snackbar.LENGTH_LONG).show();
	}
}
