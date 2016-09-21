package com.adsamcik.signalcollector.classes;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Path;
import android.support.design.widget.FloatingActionButton;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import com.adsamcik.signalcollector.Assist;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.interfaces.IValueCallback;

import java.util.ArrayList;
import java.util.List;

public class FabMenu {
	private final String TAG = "SignalsFabMenu";

	private final int FAB_TARGET_X = -150;
	private final int FAB_TARGET_Y = -50;
	private final int FAB_MOVEMENT_LENGTH = 200;
	private final int FAB_ARC_X = -75;
	private final int FAB_ARC_Y = 50;

	private FloatingActionButton fab;
	private float originalFabX;
	private float originalFabY;

	private ViewGroup wrapper;
	private ViewGroup menu;
	private IValueCallback<String> callback;

	private List<TextView> items = new ArrayList<>();

	private View.OnClickListener closeClickListener = (p) -> hide();

	public FabMenu(ViewGroup viewGroup, Context context) {
		wrapper = (ViewGroup) LayoutInflater.from(context).inflate(R.layout.fab_menu, viewGroup, false);
		menu = (ViewGroup) wrapper.getChildAt(0);
		wrapper.setVisibility(View.INVISIBLE);
		menu.setVisibility(View.INVISIBLE);
		viewGroup.addView(wrapper);
	}

	public FabMenu setFab(FloatingActionButton fab) {
		this.fab = fab;
		originalFabX = fab.getX();
		originalFabY = fab.getY();
		return this;
	}

	private void callback(String value) {
		if (callback != null)
			callback.callback(value);
		hide();
	}

	public FabMenu setCallback(IValueCallback<String> callback) {
		this.callback = callback;
		return this;
	}

	public FabMenu addItem(final String name, final Context context) {
		TextView tv = (TextView) LayoutInflater.from(context).inflate(R.layout.fab_menu_button, menu, false);
		tv.setText(name);
		tv.setOnClickListener(v -> callback(name));
		menu.addView(tv);
		items.add(tv);
		return this;
	}


	private int[] calculateRevealCenter() {
		final int fabPos[] = new int[2];
		fab.getLocationOnScreen(fabPos);
		final int menuPos[] = new int[2];
		menu.getLocationOnScreen(menuPos);

		final int result[] = new int[2];
		result[0] = fabPos[0] - menuPos[0] + fab.getWidth() / 2;
		result[1] = fabPos[1] - menuPos[1] + fab.getHeight() / 2;
		return result;
	}

	public void hide() {
		Path path = new Path();
		path.moveTo(FAB_TARGET_X, FAB_TARGET_Y);
		path.quadTo(FAB_ARC_X, FAB_ARC_Y, originalFabX, originalFabY);
		wrapper.setOnClickListener(null);
		ObjectAnimator mAnimator;
		mAnimator = ObjectAnimator.ofFloat(fab, View.X, View.Y, path);
		mAnimator.setDuration(FAB_MOVEMENT_LENGTH);
		mAnimator.setInterpolator(new AccelerateInterpolator(1.5f));
		final int pos[] = calculateRevealCenter();
		Animate.RevealHide(menu, pos[0], pos[1], () -> {
			wrapper.setVisibility(View.INVISIBLE);
			mAnimator.start();
		});
	}

	public void show(Context context) {
		if (fab == null)
			throw new NullPointerException("Fab is null");
		wrapper.setVisibility(View.VISIBLE);
		menu.setVisibility(View.INVISIBLE);
		final int fabPos[] = new int[2];
		fab.getLocationOnScreen(fabPos);

		menu.setX(fabPos[0] - Assist.dpToPx(context, 166) + fab.getWidth());
		menu.setY(fabPos[1] - menu.getHeight() / 2);

		Path path = new Path();
		path.moveTo(originalFabX, originalFabY);
		path.quadTo(FAB_ARC_X, FAB_ARC_Y, FAB_TARGET_X, FAB_TARGET_Y);

		ObjectAnimator mAnimator;
		mAnimator = ObjectAnimator.ofFloat(fab, View.X, View.Y, path);
		mAnimator.setDuration(FAB_MOVEMENT_LENGTH);
		mAnimator.setInterpolator(new DecelerateInterpolator(1f));
		mAnimator.addListener(new Animator.AnimatorListener() {

			@Override
			public void onAnimationStart(Animator animator) {

			}

			@Override
			public void onAnimationEnd(Animator animator) {
				final int pos[] = calculateRevealCenter();
				Animate.RevealShow(menu, pos[0], pos[1]);
			}

			@Override
			public void onAnimationCancel(Animator animator) {

			}

			@Override
			public void onAnimationRepeat(Animator animator) {

			}
		});
		mAnimator.start();
		wrapper.setOnClickListener(closeClickListener);
	}
}
