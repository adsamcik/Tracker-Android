package com.adsamcik.signalcollector.utility;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.ColorInt;
import android.support.annotation.NonNull;
import android.support.annotation.StringRes;
import android.support.design.widget.BottomSheetBehavior;
import android.support.design.widget.CoordinatorLayout;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.LinearLayout;

import com.adsamcik.signalcollector.R;

import java.util.ArrayList;

public class BottomSheetMenu {
	private final LinearLayout menuRoot;
	private final ArrayList<Button> menuItems;
	private final BottomSheetBehavior bottomSheetBehavior;

	private final @ColorInt int textColor;

	public BottomSheetMenu(@NonNull CoordinatorLayout root) {
		Context context = root.getContext();
		/*menuRoot = new LinearLayout(context);
		CoordinatorLayout.LayoutParams layoutParams = new CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		layoutParams.setBehavior(new BottomSheetBehavior());
		menuRoot.setLayoutParams(layoutParams);
		int dp8padding = Assist.dpToPx(context, 8);
		menuRoot.setPadding(0, dp8padding, 0, dp8padding);
		menuRoot.setOrientation(LinearLayout.VERTICAL);
		menuRoot.setBackgroundColor(ContextCompat.getColor(context, R.color.cardBackground));
		root.addView(menuRoot);*/

		LayoutInflater.from(context).inflate(R.layout.bottom_sheet_menu, root);
		menuRoot = (LinearLayout) root.getChildAt(root.getChildCount() - 1);
		bottomSheetBehavior = BottomSheetBehavior.from(menuRoot);
		bottomSheetBehavior.setPeekHeight(Assist.dpToPx(context, 54));
		bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);

		menuItems = new ArrayList<>();
		TypedValue typedValue = new TypedValue();
		//inverse text color returned weird values
		context.getTheme().resolveAttribute(R.attr.titleColor, typedValue, true);
		textColor = typedValue.data;
	}

	public void addItem(@StringRes int title, View.OnClickListener onClickListener) {
		Context context = menuRoot.getContext();
		Button button = new Button(context);
		TypedValue typedValue = new TypedValue();
		context.getTheme().resolveAttribute(R.attr.selectableItemBackground, typedValue, true);
		button.setBackgroundResource(typedValue.resourceId);
		button.setOnClickListener(onClickListener);
		button.setText(title);
		button.setTextColor(textColor);
		menuItems.add(button);

		menuRoot.addView(button);
	}

	public void removeItemAt(int index) {
		menuRoot.removeView(menuItems.get(index));
		menuItems.remove(index);
	}

	public void destroy() {
		ViewParent viewParent = menuRoot.getParent();
		if (viewParent != null)
			((ViewGroup) viewParent).removeView(menuRoot);
	}

	public void showHide(int delayInMS) {
		bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
		if(Looper.myLooper() == null)
			Looper.prepare();
		new Handler().postDelayed(() -> bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED), delayInMS);
	}
}
