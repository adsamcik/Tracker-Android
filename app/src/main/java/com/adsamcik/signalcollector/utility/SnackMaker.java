package com.adsamcik.signalcollector.utility;

import android.support.design.widget.Snackbar;
import android.view.View;

public class SnackMaker {
	private final View view;
	public SnackMaker(View view) {
		this.view = view;
	}

	public void showSnackbar(String message) {
		Snackbar snack = Snackbar.make(view, message, 4000);
		View view = snack.getView();
		view.setPadding(0, 0, 0, Assist.getNavBarHeight(view.getContext()));
		snack.show();
	}
}
