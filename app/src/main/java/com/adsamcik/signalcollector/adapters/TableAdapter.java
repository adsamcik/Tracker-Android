package com.adsamcik.signalcollector.adapters;

import android.content.Context;
import android.os.Build;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.TableLayout;
import android.widget.TextView;

import com.adsamcik.signalcollector.enums.AppendBehavior;
import com.adsamcik.signalcollector.utility.Table;

import java.util.ArrayList;
import java.util.Collections;

public class TableAdapter extends BaseAdapter {
	private final ArrayList<Table> tables;
	private final Context context;

	public TableAdapter(@NonNull Context context) {
		tables = new ArrayList<>();
		this.context = context.getApplicationContext();
	}

	public void add(Table table) {
		tables.add(table);
	}

	public void clear() {
		tables.clear();
		notifyDataSetChanged();
	}

	public void sort() {
		Collections.sort(tables, (tx, ty) -> tx.appendBehavior.ordinal() - ty.appendBehavior.ordinal());
		notifyDataSetChanged();

		Log.d("ITEMS", "#### AFTER SORT ####");
		for (Table t : tables) {
			Log.d("ITEMS", t.getTitle() + " - " + t.appendBehavior.name());
		}
	}

	public void remove(@NonNull final AppendBehavior appendBehavior) {
		if (Build.VERSION.SDK_INT >= 24)
			tables.removeIf(table -> table.appendBehavior == appendBehavior);
		else
			for (int i = 0; i < tables.size(); i++)
				if (tables.get(i).appendBehavior == appendBehavior)
					tables.remove(i--);
		notifyDataSetChanged();
	}

	@Override
	public int getCount() {
		return tables.size();
	}

	@Override
	public Object getItem(int i) {
		return tables.get(i);
	}

	@Override
	public long getItemId(int i) {
		return i;
	}

	@Override
	public View getView(int i, View view, ViewGroup viewGroup) {
		return tables.get(i).getView(context, i, getCount());
	}
}
