package com.adsamcik.signalcollector.classes;

import android.support.design.widget.Snackbar;
import android.view.View;

import com.adsamcik.signalcollector.Extensions;

public class SnackMaker {
	private View view;
	public SnackMaker(View view) {
		this.view = view;
	}

	public void showSnackbar(String message) {
		Snackbar snack = Snackbar.make(view, message, 4000);
		View view = snack.getView();
		view.setPadding(0, 0, 0, Extensions.getNavBarHeight(view.getContext()));
		snack.show();
	}
}
