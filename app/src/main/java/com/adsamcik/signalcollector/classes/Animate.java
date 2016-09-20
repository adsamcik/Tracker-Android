package com.adsamcik.signalcollector.classes;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.view.View;
import android.view.ViewAnimationUtils;

public class Animate {
	public static void RevealShow(View view) {
		int cx = view.getWidth() / 2;
		int cy = view.getHeight() / 2;
		RevealShow(view, cx, cy);
	}

	public static void RevealShow(View view, int cx, int cy) {
		float finalRadius = (float) Math.hypot(cx, cy);

		Animator anim = ViewAnimationUtils.createCircularReveal(view, cx, cy, 0, finalRadius);

		view.setVisibility(View.VISIBLE);
		anim.start();
	}

	public static void RevealHide(View view) {
		int cx = view.getWidth() / 2;
		int cy = view.getHeight() / 2;

		float initialRadius = (float) Math.hypot(cx, cy);

		Animator anim = ViewAnimationUtils.createCircularReveal(view, cx, cy, initialRadius, 0);

		anim.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation) {
				super.onAnimationEnd(animation);
				view.setVisibility(View.GONE);
			}
		});
		anim.start();
	}
}
