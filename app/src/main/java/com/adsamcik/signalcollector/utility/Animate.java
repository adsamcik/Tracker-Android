package com.adsamcik.signalcollector.utility;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.support.annotation.Nullable;
import android.view.View;
import android.view.ViewAnimationUtils;

import com.adsamcik.signalcollector.interfaces.ICallback;

public class Animate {
	/**
	 * Circular reveal animation
	 * Center is automatically set to the middle of the view
	 * Initial radius is 0
	 *
	 * @param view view
	 */
	public static void RevealShow(View view) {
		int cx = view.getWidth() / 2;
		int cy = view.getHeight() / 2;
		RevealShow(view, cx, cy, 0);
	}

	/**
	 * Circular reveal animation
	 *
	 * @param view          view
	 * @param cx            animation center x
	 * @param cy            animation center y
	 * @param initialRadius initial radius
	 */
	public static void RevealShow(View view, int cx, int cy, int initialRadius) {
		float finalRadius = (float) Math.hypot(cx, cy);

		Animator anim = ViewAnimationUtils.createCircularReveal(view, cx, cy, initialRadius, finalRadius);

		view.setVisibility(View.VISIBLE);
		anim.start();
	}

	/**
	 * Circular hide animation
	 * Center is automatically set to the middle of the view
	 * End radius is 0
	 *
	 * @param view           view
	 * @param onDoneCallback callback when animation is done
	 */
	public static void RevealHide(View view, @Nullable ICallback onDoneCallback) {
		int cx = view.getWidth() / 2;
		int cy = view.getHeight() / 2;
		RevealHide(view, cx, cy, 0, onDoneCallback);
	}

	/**
	 * Circular hide animation
	 *
	 * @param view           view
	 * @param cx             animation center x
	 * @param cy             animation center y
	 * @param endRadius      end radius
	 * @param onDoneCallback callback when animation is done
	 */
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
