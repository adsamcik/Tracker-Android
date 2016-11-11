package com.adsamcik.signalcollector.utility;

import android.app.Activity;
import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.interfaces.IValueCallback;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.List;

public class FabMenu {
	private final String TAG = "SignalsFabMenu";

	private FloatingActionButton fab;

	private ViewGroup wrapper;
	private ViewGroup menu;
	private IValueCallback<String> callback;

	private View.OnClickListener closeClickListener = (p) -> hide();

	private boolean isVisible = false;

	public FabMenu(ViewGroup viewGroup, Context context) {
		wrapper = (ViewGroup) LayoutInflater.from(context).inflate(R.layout.fab_menu, viewGroup, false);
		menu = (ViewGroup) wrapper.getChildAt(0);
		wrapper.setVisibility(View.INVISIBLE);
		menu.setVisibility(View.INVISIBLE);
		viewGroup.addView(wrapper);
	}

	public FabMenu setFab(FloatingActionButton fab) {
		this.fab = fab;
		return this;
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
		activity.runOnUiThread(() -> menu.addView(tv));
		//items.add(tv);
		return this;
	}

	public FabMenu clear(final Activity activity) {
		//items.clear();
		activity.runOnUiThread(() -> menu.removeAllViews());
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

	public void show(Context context) {
		if (fab == null)
			throw new NullPointerException("Fab is null");
		if (isVisible)
			return;
		isVisible = true;
		wrapper.setVisibility(View.VISIBLE);
		menu.setVisibility(View.INVISIBLE);
		final int fabPos[] = new int[2];
		fab.getLocationOnScreen(fabPos);

		menu.setX(context.getResources().getDisplayMetrics().widthPixels - Assist.dpToPx(context, 166));
		menu.setY(fabPos[1] - menu.getHeight() / 2 + 10);

		final int pos[] = calculateRevealCenter();
		Animate.RevealShow(menu, pos[0], pos[1], 0);
		wrapper.setOnClickListener(closeClickListener);
	}
}
