package com.adsamcik.signalcollector.utility;

import android.app.Activity;
import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.FragmentActivity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.interfaces.IValueCallback;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.List;

public class FabMenu {
	private final String TAG = "SignalsFabMenu";

	private FloatingActionButton fab;

	private final ViewGroup wrapper;
	private final ViewGroup menu;
	private final ViewGroup container;

	private IValueCallback<String> callback;

	private final View.OnClickListener closeClickListener = (p) -> hide();

	private boolean isVisible = false;
	private boolean boundsCalculated = false;

	public FabMenu(ViewGroup parent, FloatingActionButton fab, Context context) {
		wrapper = (ViewGroup) LayoutInflater.from(context).inflate(R.layout.fab_menu, parent, false);
		menu = (ViewGroup) wrapper.getChildAt(0);
		container = (ViewGroup) menu.getChildAt(0);
		wrapper.setVisibility(View.INVISIBLE);
		menu.setVisibility(View.INVISIBLE);
		parent.addView(wrapper);
		this.fab = fab;
	}

	private void callback(String value) {
		assert value != null;
		callback.callback(value);
		hide();
	}

	public FabMenu setCallback(@Nullable IValueCallback<String> callback) {
		this.callback = callback;
		return this;
	}

	public FabMenu addItems(final String jsonStringArray, final Activity activity) throws JSONException {
		JSONArray array = new JSONArray(jsonStringArray);
		for (int i = 0; i < array.length(); i++)
			addItem(array.getString(i), activity);
		return this;
	}

	public FabMenu addItems(final List<String> stringList, final Activity activity) {
		for (String item : stringList)
			addItem(item, activity);
		return this;
	}

	public FabMenu addItem(final String name, final Activity activity) {
		TextView tv = (TextView) LayoutInflater.from(activity).inflate(R.layout.fab_menu_button, menu, false);
		tv.setText(name);
		tv.setOnClickListener(v -> callback(name));
		activity.runOnUiThread(() -> container.addView(tv));
		boundsCalculated = false;
		return this;
	}

	public void recalculateBounds(@NonNull Context context) {
		int maxHeight = wrapper.getHeight() / 2;
		int height = container.getHeight();
		if (height > maxHeight) {
			height = maxHeight;
		}

		final int fabPos[] = new int[2];
		final int fabParentPos[] = new int[2];
		fab.getLocationOnScreen(fabPos);
		View parent = ((View) fab.getParent().getParent().getParent());
		parent.getLocationOnScreen(fabParentPos);

		DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
		menu.setX(displayMetrics.widthPixels - menu.getWidth() - Assist.dpToPx(context, 16));

		int halfHeight = height / 2;
		int offset = fabPos[1] + halfHeight;
		int maxY = parent.getBottom() - Assist.dpToPx(context, 16);
		if (offset > maxY)
			offset = halfHeight + (offset - maxY);
		int y = fabPos[1] - offset;
		if (y > maxY)
			y = maxY;
		menu.setY(y);

		FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(menu.getWidth(), height);
		menu.setLayoutParams(layoutParams);
		Log.d(TAG, "offset " + offset + " y " + y + " height " + menu.getHeight() + " target height " + height + " max height " + maxHeight);
		boundsCalculated = true;
	}

	public FabMenu clear(final Activity activity) {
		if (activity != null)
			activity.runOnUiThread(menu::removeAllViews);
		return this;
	}

	public FabMenu destroy(final Activity activity) {
		if (activity != null)
			activity.runOnUiThread(() -> {
				wrapper.removeAllViews();
				((ViewGroup) wrapper.getParent()).removeView(wrapper);
			});

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
		if (!isVisible)
			return;
		isVisible = false;
		wrapper.setOnClickListener(null);
		final int pos[] = calculateRevealCenter();
		Animate.RevealHide(menu, pos[0], pos[1], 0, () -> wrapper.setVisibility(View.INVISIBLE));
	}

	public void hideAndDestroy(@NonNull FragmentActivity activity) {
		if (!isVisible)
			destroy(activity);
		else {
			isVisible = false;
			wrapper.setOnClickListener(null);
			final int pos[] = calculateRevealCenter();
			Animate.RevealHide(menu, pos[0], pos[1], 0, () -> destroy(activity));
		}
	}

	public void show(@NonNull Activity activity) throws NullPointerException {
		if (fab == null)
			throw new NullPointerException("Fab is null");
		if (isVisible)
			return;
		isVisible = true;
		recalculateBounds(activity);
		wrapper.setVisibility(View.VISIBLE);
		menu.setVisibility(View.INVISIBLE);
		final int fabPos[] = new int[2];
		fab.getLocationOnScreen(fabPos);

		final int pos[] = calculateRevealCenter();
		Animate.RevealShow(menu, pos[0], pos[1], 0);
		wrapper.setOnClickListener(closeClickListener);
	}
}
