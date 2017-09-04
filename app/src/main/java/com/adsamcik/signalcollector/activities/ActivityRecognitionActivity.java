package com.adsamcik.signalcollector.activities;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.file.DataStore;
import com.adsamcik.signalcollector.adapters.FilterableAdapter;
import com.adsamcik.signalcollector.utility.Parser;
import com.adsamcik.signalcollector.utility.Preferences;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

import static com.adsamcik.signalcollector.utility.Constants.DAY_IN_MILLISECONDS;
import static java.text.DateFormat.getDateTimeInstance;

public class ActivityRecognitionActivity extends DetailActivity {
	private static final String FILE = "activityRecognitionDebug.tsv";

	private Button startStopButton;
	private FilterableAdapter adapter;
	private ListView listView;

	private static WeakReference<ActivityRecognitionActivity> instance = null;

	private static final String delim = " - ";

	private boolean usingFilter = false;

	public static void addLineIfDebug(@NonNull Context context, @NonNull String activity, @Nullable String action) {
		SharedPreferences preferences = Preferences.get(context);
		if (preferences.getBoolean(Preferences.PREF_DEV_ACTIVITY_TRACKING_ENABLED, false)) {
			if ((System.currentTimeMillis() - preferences.getLong(Preferences.PREF_DEV_ACTIVITY_TRACKING_STARTED, 0)) / DAY_IN_MILLISECONDS > 0) {
				preferences.edit().putBoolean(Preferences.PREF_DEV_ACTIVITY_TRACKING_ENABLED, false).apply();
				if (instance != null && instance.get() != null) {
					final ActivityRecognitionActivity _this = instance.get();
					_this.runOnUiThread(() -> _this.startStopButton.setText(_this.getString(R.string.start)));
				}
			}
			addLine(context, activity, action);
		}
	}

	private static void addLine(@NonNull Context context, @NonNull String activity, @Nullable String action) {
		String time = getDateTimeInstance().format(System.currentTimeMillis());
		String line = time + '\t' + activity + '\t' + (action != null ? action + '\n' : '\n');
		DataStore.saveString(context, FILE, line, true);
		if (instance != null && instance.get() != null) {
			final ActivityRecognitionActivity _this = instance.get();
			_this.runOnUiThread(() -> {
				_this.adapter.add(action == null ? new String[]{time, activity} : new String[]{time, activity, action});
				if (_this.listView.getLastVisiblePosition() == _this.adapter.getCount() - 2)
					_this.listView.smoothScrollToPosition(_this.adapter.getCount() - 1);
			});
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		instance = new WeakReference<>(this);

		View v = getLayoutInflater().inflate(R.layout.layout_activity_recognition, createContentParent(false));
		startStopButton = findViewById(R.id.dev_activity_debug_start_stop_button);

		setTitle(R.string.dev_activity_recognition_title);

		listView = v.findViewById(R.id.dev_activity_list_view);

		if (Preferences.get(this).getBoolean(Preferences.PREF_DEV_ACTIVITY_TRACKING_ENABLED, false))
			startStopButton.setText(getString(R.string.stop));
		else
			startStopButton.setText(getString(R.string.start));

		startStopButton.setOnClickListener(view -> {
			SharedPreferences sp = Preferences.get(this);
			boolean setEnabled = !sp.getBoolean(Preferences.PREF_DEV_ACTIVITY_TRACKING_ENABLED, false);
			SharedPreferences.Editor editor = sp.edit();
			editor.putBoolean(Preferences.PREF_DEV_ACTIVITY_TRACKING_ENABLED, setEnabled);
			if (setEnabled) {
				startStopButton.setText(getString(R.string.stop));
				editor.putLong(Preferences.PREF_DEV_ACTIVITY_TRACKING_STARTED, System.currentTimeMillis());
			} else
				startStopButton.setText(getString(R.string.start));
			editor.apply();
		});

		final Activity activity = this;

		new Thread() {
			@Override
			public void run() {
				ArrayList<String[]> items = Parser.parseTSVFromFile(activity, FILE);
				if (items == null)
					items = new ArrayList<>();
				adapter = new FilterableAdapter(activity, R.layout.spinner_item, items, delim);
				listView.setAdapter(adapter);
				listView.setSelection(items.size() - 1);
			}
		}.run();

		findViewById(R.id.dev_activity_recognition_filter).setOnClickListener(f -> {
			if (usingFilter) {
				adapter.getFilter().filter(null);
				((Button) f).setText(R.string.dev_activity_recognition_hide);
			} else {
				((Button) f).setText(R.string.dev_activity_recognition_show);
				adapter.getFilter().filter(".*" + delim + ".*" + delim + ".*");
			}

			usingFilter = !usingFilter;
		});

		findViewById(R.id.dev_activity_recognition_clear).setOnClickListener(f -> {
			adapter.clear();
			DataStore.delete(this, FILE);
		});
	}
}
