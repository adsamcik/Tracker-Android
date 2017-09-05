package com.adsamcik.signalcollector.utility;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.CardView;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.enums.AppendBehavior;

import java.util.ArrayList;
import java.util.Locale;

public class Table {
	public final AppendBehavior appendBehavior;

	private ViewGroup view = null;

	public String getTitle() {
		return title;
	}

	private String title = null;
	private final ArrayList<Pair<String, String>> data;
	private ArrayList<Pair<String, View.OnClickListener>> buttons = null;

	private final boolean showNumber;

	private final int marginDp;

	/**
	 * Table constructor
	 *
	 * @param rowCount   number of data (used to initialize array holding data)
	 * @param showNumber show number of row (starts at 1)
	 */
	public Table(int rowCount, boolean showNumber, int marginDp, @NonNull AppendBehavior appendBehavior) {
		this.data = new ArrayList<>(rowCount);
		this.showNumber = showNumber;
		this.appendBehavior = appendBehavior;
		this.marginDp = marginDp;
	}

	/*public void addToViewGroup(@NonNull ViewGroup viewGroup, @NonNull Context context, int index, boolean animate, long delay) {
		if (index >= 0 && index < viewGroup.getChildCount())
			viewGroup.addView(view, index);
		else
			viewGroup.addView(view);

		if (animate) {
			view.setTranslationY(viewGroup.getHeight());
			view.setAlpha(0);
			view.animate()
					.translationY(0)
					.setInterpolator(new DecelerateInterpolator(3.f))
					.setDuration(700)
					.setStartDelay(delay)
					.alpha(1)
					.start();
		}
	}*/

	/**
	 * Sets single title for whole table
	 *
	 * @param title title
	 * @return this table
	 */
	public Table setTitle(String title) {
		this.title = title;
		return this;
	}

	/**
	 * Add button to the bottom of the table
	 *
	 * @param text     title of the button
	 * @param callback on click callback
	 * @return this table
	 */
	public Table addButton(String text, View.OnClickListener callback) {
		if(buttons == null)
			buttons = new ArrayList<>(2);
		buttons.add(new Pair<>(text, callback));
		return this;
	}

	/**
	 * Adds data to 2 columns on the last row, only use this with 2 columns (+1 if row numbering is enabled)
	 *
	 * @param name  row name
	 * @param value row value
	 * @return this table
	 */
	public Table addData(String name, String value) {
		data.add(new Pair<>(name, value));
		return this;
	}

	private TableRow generateButtonsRow(@NonNull Context context) {
		if (buttons != null) {
			DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
			TableRow row = new TableRow(context);
			TableLayout.LayoutParams lp = new TableLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			lp.topMargin = Assist.dpToPx(displayMetrics, 4);
			row.setLayoutParams(lp);

			for (int i = 0; i < buttons.size(); i++)
				row.addView(generateButton(context, displayMetrics, i));
			return row;
		}
		return null;
	}

	private TextView generateButton(@NonNull Context context, DisplayMetrics displayMetrics, int index) {
		TextView button = new TextView(context);
		button.setMinWidth(Assist.dpToPx(displayMetrics, 48));
		button.setPadding(Assist.dpToPx(displayMetrics, 16), 0, Assist.dpToPx(displayMetrics, 16), 0);
		button.setHeight(Assist.dpToPx(displayMetrics, 48));
		button.setText(buttons.get(index).first.toUpperCase());
		button.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));
		button.setOnClickListener(buttons.get(index).second);
		button.setTextSize(16);
		button.setGravity(Gravity.CENTER);
		button.setBackground(Assist.getPressedColorRippleDrawable(0, ContextCompat.getColor(context, R.color.color_accent), context.getDrawable(R.drawable.rectangle)));
		return button;
	}

	private TableRow generateDataRow(@NonNull Context context, int index) {
		TableRow row = new TableRow(context);
		row.setPadding(0, 0, 0, 20);

		if (showNumber) {
			TextView rowNum = new TextView(context);
			rowNum.setText(String.format(Locale.UK, "%d", index + 1));
			rowNum.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 0.5f));
			rowNum.setTextSize(16);
			row.addView(rowNum);
		}

		TextView textId = new TextView(context);
		textId.setText(data.get(index).first);
		textId.setTextSize(15);
		textId.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 3f));
		row.addView(textId);

		TextView textValue = new TextView(context);
		String value = data.get(index).second;
		try {
			textValue.setText(Assist.formatNumber(Integer.parseInt(value)));
		} catch (NumberFormatException e) {
			textValue.setText(value);
		}
		textValue.setTextSize(15);
		textValue.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 2f));
		textValue.setGravity(Gravity.END);
		row.addView(textValue);

		return row;
	}

	public View getView(@NonNull Context context, boolean requireWrapper) {
		//todo caching breaks shadows
		/*if (view != null)
			return view;*/

		Resources r = context.getResources();

		CardView cardView = new CardView(context);

		TableLayout layout = new TableLayout(context);

		int hPadding = (int) r.getDimension(R.dimen.activity_horizontal_margin);
		layout.setPadding(hPadding, 30, hPadding, 30);

		if (title != null) {
			TextView label = new TextView(context);
			label.setTextSize(18);
			label.setText(title);
			label.setTypeface(null, Typeface.BOLD);
			label.setGravity(Gravity.CENTER);
			label.setPadding(0, 0, 0, 30);
			layout.addView(label, 0);
		}

		for (int i = 0; i < data.size(); i++)
			layout.addView(generateDataRow(context, i));

		TableRow buttonsRow = generateButtonsRow(context);
		if (buttonsRow != null)
			layout.addView(buttonsRow);

		cardView.addView(layout);

		if (marginDp != 0 || requireWrapper) {
			FrameLayout frameLayout = new FrameLayout(context);

			if(marginDp != 0) {
				FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
				int margin = Assist.dpToPx(context, this.marginDp);
				layoutParams.setMargins(margin, margin, margin, margin);
				cardView.setLayoutParams(layoutParams);
			}
			frameLayout.addView(cardView);
			return view = frameLayout;
		}

		return view = cardView;
	}

	/**
	 * Removed all data from the table
	 *
	 * @return this table
	 */
	public Table clear() {
		if (view != null) {
			view.removeAllViewsInLayout();
			view = null;
		}
		data.clear();
		return this;
	}
}
