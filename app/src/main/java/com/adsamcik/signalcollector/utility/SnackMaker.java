package com.adsamcik.signalcollector.utility;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.IntDef;
import android.support.annotation.IntRange;
import android.support.annotation.IntegerRes;
import android.support.annotation.NonNull;
import android.support.annotation.StringRes;
import android.support.design.widget.BaseTransientBottomBar;
import android.support.design.widget.Snackbar;
import android.util.Pair;
import android.view.View;

import com.adsamcik.signalcollector.R;
import com.google.android.gms.tasks.Task;

import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

public class SnackMaker {
	private final View view;

	private final Queue<Pair<String, Integer>> queue = new LinkedBlockingQueue<>();
	private Snackbar current;
	private Handler handler;

	public SnackMaker(View view) {
		this.view = view;
	}

	public SnackMaker(Activity activity) {
		this.view = activity.findViewById(R.id.fabCoordinator);
	}

	public void showSnackbar(@NonNull String message) {
		showSnackbar(message, Snackbar.LENGTH_LONG);
	}

	public void showSnackbar(@NonNull String message, @IntRange(from = Snackbar.LENGTH_SHORT, to = Snackbar.LENGTH_LONG) int duration) {
		if (queue.isEmpty()) {
			queue.add(new Pair<>(message, duration));
			next();
		} else
			queue.add(new Pair<>(message, duration));
	}

	public void showSnackbar(@StringRes int message, @IntRange(from = Snackbar.LENGTH_SHORT, to = Snackbar.LENGTH_LONG) int duration) {
		showSnackbar(view.getContext().getString(message), duration);
	}

	public void showSnackbar(@StringRes int message, @StringRes int action, @NonNull View.OnClickListener onClickListener) {
		Snackbar.make(view, message, Snackbar.LENGTH_LONG).setAction(action, onClickListener).show();
	}

	public void interrupt() {
		current.dismiss();
		queue.remove();
		handler = null;
	}

	private void next() {
		if (current != null)
			queue.remove();
		if (!queue.isEmpty()) {
			current = Snackbar.make(view, queue.peek().first, queue.peek().second);

			current.show();
			if (handler == null) {
				if (Looper.myLooper() == null)
					Looper.prepare();
				handler = new Handler();
			}
			handler.postDelayed(this::next, queue.peek().second == Snackbar.LENGTH_LONG ? 3500 : 2000);
		}
	}
}
