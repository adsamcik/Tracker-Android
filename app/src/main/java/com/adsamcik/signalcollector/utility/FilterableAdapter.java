package com.adsamcik.signalcollector.utility;

import android.content.Context;
import android.os.Build;
import android.support.annotation.LayoutRes;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FilterableAdapter extends BaseAdapter implements Filterable {
	private final String delim;

	private final List<String[]> originalData;
	private final List<String> originalDataSerialized;
	private List<String> filteredData;
	private final LayoutInflater mInflater;
	private final int res;
	private final ItemFilter mFilter = new ItemFilter();

	private String serializeLine(String[] line) {
		if (Build.VERSION.SDK_INT >= 26)
			return String.join(delim, line);
		else {
			StringBuilder builder = new StringBuilder();
			for (String s : line)
				builder.append(s).append(delim);
			builder.setLength(builder.length() - delim.length());
			return builder.toString();
		}
	}

	public FilterableAdapter(Context context, @LayoutRes int resource, List<String[]> items, String delimeter) {
		delim = delimeter;
		this.originalData = items;

		originalDataSerialized = new ArrayList<>(items.size());
		for (String[] line : items)
			originalDataSerialized.add(serializeLine(line));

		this.filteredData = originalDataSerialized;
		mInflater = LayoutInflater.from(context);
		res = resource;
	}

	public int getCount() {
		return filteredData.size();
	}

	public Object getItem(int position) {
		return filteredData.get(position);
	}

	public long getItemId(int position) {
		return position;
	}

	public View getView(int position, View convertView, ViewGroup parent) {
		// A ViewHolder keeps references to children views to avoid unnecessary calls
		// to findViewById() on each row.
		ViewHolder holder;

		// When convertView is not null, we can reuse it directly, there is no need
		// to reinflate it. We only inflate a new View when the convertView supplied
		// by ListView is null.
		if (convertView == null) {
			convertView = mInflater.inflate(res, null);

			// Creates a ViewHolder and store references to the two children views
			// we want to bind data to.
			holder = new ViewHolder();
			holder.text = (TextView) convertView;

			// Bind the data efficiently with the holder.

			convertView.setTag(holder);
		} else {
			// Get the ViewHolder back to get fast access to the TextView
			// and the ImageView.
			holder = (ViewHolder) convertView.getTag();
		}

		// If weren't re-ordering this you could rely on what you set last time
		holder.text.setText(filteredData.get(position));

		return convertView;
	}

	static class ViewHolder {
		TextView text;
	}

	public Filter getFilter() {
		return mFilter;
	}

	public void add(String[] item) {
		originalData.add(item);
		originalDataSerialized.add(serializeLine(item));
		notifyDataSetChanged();
	}

	public void clear() {
		originalData.clear();
		originalDataSerialized.clear();
		notifyDataSetChanged();
	}

	private class ItemFilter extends Filter {
		@Override
		protected FilterResults performFiltering(CharSequence constraint) {
			FilterResults results = new FilterResults();
			if (constraint == null) {
				results.values = originalDataSerialized;
				results.count = originalDataSerialized.size();
			} else {
				int count = originalDataSerialized.size();
				final ArrayList<String> nlist = new ArrayList<>(count);

				Pattern pattern = Pattern.compile(constraint.toString());

				for (int i = 0; i < count; i++) {
					String filterableString = originalDataSerialized.get(i);
					Matcher matcher = pattern.matcher(filterableString);
					if (matcher.find()) {
						nlist.add(filterableString);
					}
				}

				results.values = nlist;
				results.count = nlist.size();
			}
			return results;
		}

		@SuppressWarnings("unchecked")
		@Override
		protected void publishResults(CharSequence constraint, FilterResults results) {
			filteredData = (ArrayList<String>) results.values;
			notifyDataSetChanged();
		}

	}
}
