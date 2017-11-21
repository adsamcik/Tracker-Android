package com.adsamcik.signalcollector.utility;

import android.app.Activity;
import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.FragmentActivity;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ListView;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.adapters.FilterableAdapter;
import com.adsamcik.signalcollector.interfaces.IFilterRule;
import com.adsamcik.signalcollector.interfaces.INonNullValueCallback;
import com.adsamcik.signalcollector.interfaces.IString;

import java.util.List;

public class FabMenu<T> {
	private final String TAG = "SignalsFabMenu";

	private FloatingActionButton fab;

	private final ViewGroup wrapper;
	private final ListView listView;

	private final FilterableAdapter<T> adapter;

	private INonNullValueCallback<String> callback;

	private final View.OnClickListener closeClickListener = (p) -> hide();

	private boolean isVisible = false;
	private boolean boundsCalculated = false;

	public FabMenu(ViewGroup parent, FloatingActionButton fab, Activity activity, @Nullable IFilterRule<T> filterRule, @NonNull IString<T> toString) {
		wrapper = (ViewGroup) LayoutInflater.from(activity).inflate(R.layout.fab_menu, parent, false);
		listView = (ListView) wrapper.getChildAt(0);
		wrapper.setVisibility(View.INVISIBLE);
		listView.setVisibility(View.INVISIBLE);

		adapter = new FilterableAdapter<>(activity, R.layout.spinner_item, null, filterRule, toString);

		listView.setAdapter(adapter);
		listView.setOnItemClickListener((adapterView, view, i, l) -> callback(adapter.getItem(i)));

		activity.runOnUiThread(() -> parent.addView(wrapper));
		this.fab = fab;
	}

	private void callback(@NonNull String value) {
		callback.callback(value);
		hide();
	}

	public FabMenu setCallback(@Nullable INonNullValueCallback<String> callback) {
		this.callback = callback;
		return this;
	}

	public FabMenu addItems(final @NonNull List<T> itemList) {
		for (T item : itemList)
			addItem(item);
		return this;
	}

	public FabMenu addItem(final @NonNull T item) {
		adapter.add(item, null);
		boundsCalculated = false;
		return this;
	}

	public FabMenu addItem(final @NonNull T item, Activity activity) {
		adapter.add(item, activity);
		boundsCalculated = false;
		return this;
	}

	public int getItemCount() {
		return adapter.getCount();
	}

	public void recalculateBounds(@NonNull Context context) {
		if (boundsCalculated)
			return;

		final int dp16px = Assist.dpToPx(context, 16);

		int maxHeight = wrapper.getHeight() / 2;
		int height;
		if(adapter.getCount() == 0)
			height = 0;
		else {
			View item = adapter.getView(0, null, null);
			item.measure(
					View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
					View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
			int measureHeight = item.getMeasuredHeight();
			height = measureHeight * adapter.getCount();
		}
		int minHeight = fab.getHeight() + dp16px;
		if (height > maxHeight)
			height = maxHeight;
		else if (height < minHeight)
			height = minHeight;

		final int fabPos[] = new int[2];
		final int fabParentPos[] = new int[2];
		final int wrapperPos[] = new int[2];
		fab.getLocationOnScreen(fabPos);
		wrapper.getLocationOnScreen(wrapperPos);
		View parent = ((View) fab.getParent().getParent().getParent());
		parent.getLocationOnScreen(fabParentPos);


		DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
		listView.setX(displayMetrics.widthPixels - listView.getWidth() - dp16px);

		fabPos[1] += fab.getHeight() / 2;
		int halfHeight = height / 2;
		int offset = halfHeight;
		int botY = fabPos[1] + halfHeight;
		int maxY = wrapperPos[1] + wrapper.getHeight() - dp16px - Assist.dpToPx(context, 56);
		if (botY > maxY)
			offset += (botY - maxY);

		int y = fabPos[1] - offset;
		listView.setY(y);

		FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(listView.getWidth(), height);
		listView.setLayoutParams(layoutParams);
		boundsCalculated = true;
		//Log.d(TAG, "offset " + offset + " y " + y + " max y " + maxY + " bot y " + botY + " height " + menu.getHeight() + " target height " + height + " max height " + maxHeight);
	}

	public FabMenu clear(final Activity activity) {
		if (activity != null)
			adapter.clear();
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
		listView.getLocationOnScreen(menuPos);

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
		Animate.INSTANCE.revealHide(listView, pos[0], pos[1], 0, () -> wrapper.setVisibility(View.INVISIBLE));
	}

	public void hideAndDestroy(@NonNull FragmentActivity activity) {
		if (!isVisible)
			destroy(activity);
		else {
			isVisible = false;
			wrapper.setOnClickListener(null);
			final int pos[] = calculateRevealCenter();
			Animate.INSTANCE.revealHide(listView, pos[0], pos[1], 0, () -> destroy(activity));
		}
	}

	public void show(@NonNull Activity activity) throws NullPointerException {
		if (fab == null)
			throw new NullPointerException("Fab is null");
		if (isVisible)
			return;

		adapter.getFilter().filter(" ", i -> {
			isVisible = true;
			boundsCalculated = false;

			recalculateBounds(activity);
			wrapper.setVisibility(View.VISIBLE);
			listView.setVisibility(View.INVISIBLE);
			final int fabPos[] = new int[2];
			fab.getLocationOnScreen(fabPos);

			final int pos[] = calculateRevealCenter();
			Animate.INSTANCE.revealShow(listView, pos[0], pos[1], 0);
			wrapper.setOnClickListener(closeClickListener);
		});
	}
}
