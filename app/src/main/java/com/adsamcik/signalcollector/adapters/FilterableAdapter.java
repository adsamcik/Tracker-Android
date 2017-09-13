package com.adsamcik.signalcollector.adapters;

import android.content.Context;
import android.support.annotation.LayoutRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.TextView;

import com.adsamcik.signalcollector.interfaces.IFilterRule;
import com.adsamcik.signalcollector.interfaces.IString;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FilterableAdapter<T> extends BaseAdapter implements Filterable {
	private final List<T> dataList;
	private final ArrayList<String> stringDataList;
	private ArrayList<String> filteredData;

	private final IString<T> stringMethod;

	private final LayoutInflater mInflater;
	private final @LayoutRes
	int res;
	private final ItemFilter mFilter;

	public FilterableAdapter(@NonNull Context context, @LayoutRes int resource, @Nullable List<T> items, @Nullable IFilterRule<T> filterRule, @NonNull IString<T> stringMethod) {
		if (items == null) {
			this.dataList = new ArrayList<>();
			this.stringDataList = new ArrayList<>();
			this.filteredData = stringDataList;
		} else {
			this.dataList = items;
			this.stringDataList = new ArrayList<>(items.size());
			for (T item : items)
				this.stringDataList.add(stringMethod.stringify(item));

			this.filteredData = stringDataList;
		}

		mFilter = new ItemFilter(filterRule);
		this.stringMethod = stringMethod;

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

	public void add(T item) {
		dataList.add(item);
		stringDataList.add(stringMethod.stringify(item));
		notifyDataSetChanged();
	}

	public void clear() {
		dataList.clear();
		notifyDataSetChanged();
	}

	private class ItemFilter extends Filter {
		private final IFilterRule<T> filterRule;

		private ItemFilter(@Nullable IFilterRule<T> filterRule) {
			this.filterRule = filterRule;
		}

		@Override
		protected FilterResults performFiltering(CharSequence constraint) {
			FilterResults results = new FilterResults();
			if (constraint == null) {
				results.values = stringDataList;
				results.count = stringDataList.size();
			} else if(dataList == null) {
				results.values = new ArrayList<>(0);
				results.count = 0;
			} else{
				final int count = stringDataList.size();
				final ArrayList<String> nlist = new ArrayList<>(count);

				if (filterRule != null) {
					for (int i=0; i < count; i++) {
						String stringified = stringDataList.get(i);
						if(filterRule.filter(dataList.get(i), stringified, constraint))
							nlist.add(stringified);
					}
				} else {
					Pattern pattern = Pattern.compile(constraint.toString());

					for (int i = 0; i < count; i++) {
						String filterableString = stringDataList.get(i);
						Matcher matcher = pattern.matcher(filterableString);
						if (matcher.find()) {
							nlist.add(filterableString);
						}
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
