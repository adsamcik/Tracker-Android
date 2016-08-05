package com.adsamcik.signalcollector.classes;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.Gravity;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.adsamcik.signalcollector.R;
import com.google.firebase.crash.FirebaseCrash;

import java.util.ArrayList;
import java.util.Locale;

public class Table {
	private final TableLayout layout;
	private final Context context;
	private final ArrayList<TableRow> rows;
	private final boolean showIndex;

	/**
	 * Table constructor
	 *
	 * @param context   context
	 * @param rowCount  number of rows (used to initialze array holding rows)
	 * @param showIndex should index be the first column?
	 */
	public Table(Context context, int rowCount, boolean showIndex) {
		this.context = context;
		this.rows = new ArrayList<>(rowCount);
		this.showIndex = showIndex;

		layout = new TableLayout(context);
		Resources r = context.getResources();
		int hPadding = (int) r.getDimension(R.dimen.activity_horizontal_margin);
		layout.setPadding(hPadding, 30, hPadding, 30);
		layout.setBackgroundColor(ContextCompat.getColor(context, R.color.cardBackground));
		layout.setElevation(r.getDimension(R.dimen.main_card_elevation));

		TableLayout.LayoutParams lp = new TableLayout.LayoutParams();
		lp.topMargin = (int) r.getDimension(R.dimen.activity_vertical_margin);
		layout.setLayoutParams(lp);
	}

	/**
	 * Sets single title for whole table
	 * @param title title
	 * @return  this table
	 */
	public Table setTitle(String title) {
		TextView label = new TextView(context);
		label.setTextSize(18);
		label.setText(title);
		label.setTypeface(null, Typeface.BOLD);
		label.setGravity(Gravity.CENTER);
		label.setPadding(0, 0, 0, 30);
		layout.addView(label, 0);
		return this;
	}

	/**
	 * Adds new row to the table
	 * @return  this table
	 */
	public Table addRow() {
		TableRow row = new TableRow(context);
		row.setPadding(0, 0, 0, 20);

		if (showIndex) {
			TextView rowNum = new TextView(context);
			rowNum.setText(String.format(Locale.UK, "%d", rows.size()));
			rowNum.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 0.5f));
			rowNum.setTextSize(15);
			row.addView(rowNum);
		}

		rows.add(row);
		layout.addView(row);
		return this;
	}

	/**
	 * Adds data to 2 columns on the last row, only use this with 2 columns (+1 if row numbering is enabled)
	 * @param name  row name
	 * @param value row value
	 * @return  this table
	 */
	public Table addData(String name, String value) {
		if (rows.size() == 0) {
			Log.e("Signals", "You must add row first");
			FirebaseCrash.log("name: " + name + " value: " + value);
			FirebaseCrash.report(new Throwable("You must add row first"));
		}
		return addData(name, value, rows.get(rows.size() - 1));
	}

	/**
	 * Adds data to 2 columns on the passed row, only use this with 2 columns (+1 if row numbering is enabled)
	 * @param name  row name
	 * @param value row value
	 * @param row   row
	 * @return  this table
	 */
	public Table addData(String name, String value, TableRow row) {
		TextView textId = new TextView(context);
		textId.setText(name);
		textId.setTextSize(15);
		textId.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 5f));
		row.addView(textId);

		TextView textValue = new TextView(context);
		textValue.setText(value);
		textValue.setTextSize(15);
		textValue.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 2f));
		textValue.setGravity(Gravity.END);
		row.addView(textValue);
		return this;
	}

	/**
	 * Removed all rows from the table
	 * @return this table
	 */
	public Table clear() {
		layout.removeAllViewsInLayout();
		rows.clear();
		return this;
	}

	/**
	 * Returns table layout
	 * @return  layout
	 */
	public TableLayout getLayout() {
		return layout;
	}
}
