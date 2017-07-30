package com.adsamcik.signalcollector.adapters;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.data.Stat;
import com.adsamcik.signalcollector.data.StatData;
import com.adsamcik.signalcollector.utility.Table;

import java.util.ArrayList;

public class StatAdapter extends BaseAdapter {
	private final ArrayList<Table> tables;
	private final Context context;

	public StatAdapter(@NonNull Context context) {
		tables = new ArrayList<>();
		this.context = context.getApplicationContext();
	}

	public void add() {

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
		if (view != null)
			return view;
		else
			return tables.get(i).getView(context);
	}
}
