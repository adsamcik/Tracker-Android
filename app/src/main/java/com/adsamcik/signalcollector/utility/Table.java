package com.adsamcik.signalcollector.utility;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.CardView;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.adsamcik.signalcollector.R;
import com.google.firebase.crash.FirebaseCrash;

import java.util.ArrayList;
import java.util.Locale;

public class Table {
	private final CardView card;
	private final TableLayout layout;
	private final Context context;
	private final ArrayList<TableRow> rows;
	private final boolean showNumber;

	private final int textColor;

	private TableRow buttonRow;

	/**
	 * Table constructor
	 *
	 * @param context    context
	 * @param rowCount   number of rows (used to initialize array holding rows)
	 * @param showNumber show number of row (starts at 1)
	 */
	public Table(Context context, int rowCount, boolean showNumber, int textColor) {
		this.context = context;
		this.rows = new ArrayList<>(rowCount);
		this.showNumber = showNumber;
		this.textColor = textColor;

		Resources r = context.getResources();

		card = new CardView(context);
		TableLayout.LayoutParams lp = new TableLayout.LayoutParams();
		lp.topMargin = (int) r.getDimension(R.dimen.activity_vertical_margin);
		card.setLayoutParams(lp);
		card.setCardBackgroundColor(r.getColor(R.color.cardview_dark_background));

		layout = new TableLayout(context);

		int hPadding = (int) r.getDimension(R.dimen.activity_horizontal_margin);
		layout.setPadding(hPadding, 30, hPadding, 30);
		card.addView(layout);
	}

	public void addToViewGroup(ViewGroup viewGroup, int index, boolean animate, long delay) {
		if (index >= 0 && index < viewGroup.getChildCount())
			viewGroup.addView(card, index);
		else
			viewGroup.addView(card);

		if (animate) {
			card.setTranslationY(viewGroup.getHeight());
			card.setAlpha(0);
			card.animate()
					.translationY(0)
					.setInterpolator(new DecelerateInterpolator(3.f))
					.setDuration(700)
					.setStartDelay(delay)
					.alpha(1)
					.start();
		}
	}

	public void addToViewGroup(ViewGroup viewGroup, boolean animate, long delay) {
		addToViewGroup(viewGroup, -1, animate, delay);
	}

	/**
	 * Sets single title for whole table
	 *
	 * @param title title
	 * @return this table
	 */
	public Table addTitle(String title) {
		TextView label = new TextView(context);
		label.setTextSize(18);
		label.setText(title);
		label.setTextColor(textColor);
		label.setTypeface(null, Typeface.BOLD);
		label.setGravity(Gravity.CENTER);
		label.setPadding(0, 0, 0, 30);
		layout.addView(label, 0);
		return this;
	}

	/**
	 * Adds new row to the table
	 *
	 * @return this table
	 */
	public Table addRow() {
		TableRow row = new TableRow(context);
		row.setPadding(0, 0, 0, 20);

		if (showNumber) {
			TextView rowNum = new TextView(context);
			rowNum.setText(String.format(Locale.UK, "%d", rows.size() + 1));
			rowNum.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 0.5f));
			rowNum.setTextSize(16);
			rowNum.setTextColor(textColor);
			row.addView(rowNum);
		}

		rows.add(row);
		layout.addView(row);
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
		DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
		if (buttonRow == null) {
			buttonRow = new TableRow(context);
			TableLayout.LayoutParams lp = new TableLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			lp.topMargin = Assist.dpToPx(displayMetrics, 4);
			buttonRow.setLayoutParams(lp);
			layout.addView(buttonRow);
		}


		TextView button = new TextView(context);
		button.setMinWidth(Assist.dpToPx(displayMetrics, 48));
		button.setPadding(Assist.dpToPx(displayMetrics, 16), 0, Assist.dpToPx(displayMetrics, 16), 0);
		button.setHeight(Assist.dpToPx(displayMetrics, 48));
		button.setText(text.toUpperCase());
		button.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));
		button.setOnClickListener(callback);
		button.setTextSize(16);
		button.setGravity(Gravity.CENTER);
		button.setBackground(Assist.getPressedColorRippleDrawable(0, ContextCompat.getColor(context, R.color.colorAccent), context.getDrawable(R.drawable.rectangle)));
		buttonRow.addView(button);
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
		if (rows.size() == 0) {
			FirebaseCrash.log("name: " + name + " value: " + value);
			FirebaseCrash.report(new Throwable("You must add row first"));
		}

		return addData(name, value, rows.get(rows.size() - 1));
	}

	/**
	 * Adds data to 2 columns on the passed row, only use this with 2 columns (+1 if row numbering is enabled)
	 *
	 * @param name  row name
	 * @param value row value
	 * @param row   row
	 * @return this table
	 */
	public Table addData(String name, String value, TableRow row) {
		TextView textId = new TextView(context);
		textId.setText(name);
		textId.setTextColor(textColor);
		textId.setTextSize(15);
		textId.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 3f));
		row.addView(textId);

		TextView textValue = new TextView(context);
		try {
			textValue.setText(Assist.formatNumber(Integer.parseInt(value)));
		} catch (NumberFormatException e) {
			textValue.setText(value);
		}
		textValue.setTextSize(15);
		textValue.setTextColor(textColor);
		textValue.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 2f));
		textValue.setGravity(Gravity.END);
		row.addView(textValue);
		return this;
	}

	/**
	 * Removed all rows from the table
	 *
	 * @return this table
	 */
	public Table clear() {
		layout.removeAllViewsInLayout();
		rows.clear();
		buttonRow = null;
		return this;
	}

	public void destroy(Activity activity) {
		activity.runOnUiThread(() -> {
			LinearLayout ll = ((LinearLayout) card.getParent());
			if (ll != null)
				ll.removeView(card);
		});
	}

	/**
	 * Returns table layout
	 *
	 * @return layout
	 */
	public TableLayout getLayout() {
		return layout;
	}
}
