package com.adsamcik.signalcollector;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.support.design.widget.TabLayout;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.Gravity;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.google.firebase.crash.FirebaseCrash;

import java.util.ArrayList;
import java.util.Locale;

public class Table {
	private TableLayout layout;
	private Context context;
	private ArrayList<TableRow> rows;
	private boolean showIndex;

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

	public Table addData(String name, String value) {
		if (rows.size() == 0) {
			Log.e("Signals", "You must add row first");
			FirebaseCrash.log("name: " + name + " value: " + value);
			FirebaseCrash.report(new Throwable("You must add row first"));
		}
		return addData(name, value, rows.get(rows.size() - 1));
	}

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

	public Table clear() {
		layout.removeAllViewsInLayout();
		rows.clear();
		return this;
	}

	public TableLayout getLayout() {
		return layout;
	}
}
