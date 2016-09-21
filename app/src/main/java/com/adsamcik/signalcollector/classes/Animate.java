package com.adsamcik.signalcollector.classes;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.support.annotation.Nullable;
import android.view.View;
import android.view.ViewAnimationUtils;

import com.adsamcik.signalcollector.interfaces.ICallback;

public class Animate {
	public static void RevealShow(View view) {
		int cx = view.getWidth() / 2;
		int cy = view.getHeight() / 2;
		RevealShow(view, cx, cy, 0);
	}

	public static void RevealShow(View view, int cx, int cy, int intialRadius) {
		float finalRadius = (float) Math.hypot(cx, cy);

		Animator anim = ViewAnimationUtils.createCircularReveal(view, cx, cy, intialRadius, finalRadius);

		view.setVisibility(View.VISIBLE);
		anim.start();
	}

	public static void RevealHide(View view, @Nullable ICallback onDoneCallback) {
		int cx = view.getWidth() / 2;
		int cy = view.getHeight() / 2;
		RevealHide(view, cx, cy, 0, onDoneCallback);
	}

	public static void RevealHide(View view, int cx, int cy, int endRadius, @Nullable ICallback onDoneCallback) {
		float initialRadius = (float) Math.hypot(cx, cy);

		Animator anim = ViewAnimationUtils.createCircularReveal(view, cx, cy, initialRadius, endRadius);
		anim.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation) {
				super.onAnimationEnd(animation);
				view.setVisibility(View.INVISIBLE);
				if (onDoneCallback != null)
					onDoneCallback.callback();
			}
		});
		anim.start();
	}

}
