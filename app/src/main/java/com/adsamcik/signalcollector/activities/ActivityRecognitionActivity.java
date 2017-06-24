package com.adsamcik.signalcollector.activities;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.DataStore;
import com.adsamcik.signalcollector.utility.Parser;
import com.adsamcik.signalcollector.utility.Preferences;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

import static java.text.DateFormat.getDateTimeInstance;

public class ActivityRecognitionActivity extends DetailActivity {
	private static final String FILE = "activityRecognitionDebug.tsv";

	private Button startStopButton;
	private ArrayList<String> arrayList;
	private ArrayAdapter<String> adapter;

	private static WeakReference<ActivityRecognitionActivity> instance = null;

	public static void addLineIfDebug(String activity, String action, @NonNull Context context) {
		SharedPreferences preferences = Preferences.get(context);
		if (preferences.getBoolean(Preferences.PREF_DEV_ACTIVITY_TRACKING_ENABLED, false)) {
			if ((System.currentTimeMillis() - preferences.getLong(Preferences.PREF_DEV_ACTIVITY_TRACKING_STARTED, 0)) / Assist.DAY_IN_MILLISECONDS > 1)
				preferences.edit().putBoolean(Preferences.PREF_DEV_ACTIVITY_TRACKING_ENABLED, false).apply();
			addLine(activity, action);
		}
	}

	private static void addLine(String activity, String action) {
		String line = getDateTimeInstance().format(System.currentTimeMillis()) + '\t' + activity + '\t' + action + '\n';
		DataStore.saveStringAppend(FILE, line);
		if (instance != null && instance.get() != null) {
			final ActivityRecognitionActivity _this = instance.get();
			_this.runOnUiThread(() -> _this.adapter.add(line));
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		instance = new WeakReference<>(this);

		View v = getLayoutInflater().inflate(R.layout.layout_activity_recognition, createContentParent(false));
		startStopButton = findViewById(R.id.dev_activity_debug_start_stop_button);

		setTitle(R.string.dev_activity_recognition_title);

		final ListView listView = v.findViewById(R.id.dev_activity_list_view);

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
				if (items == null) {
					arrayList = new ArrayList<>();
					return;
				}

				final String delim = ", ";
				arrayList = new ArrayList<>(items.size());
				for (String[] arr : items) {
					if (Build.VERSION.SDK_INT >= 26)
						arrayList.add(String.join(delim, arr));
					else {
						StringBuilder builder = new StringBuilder();
						for (String s : arr)
							builder.append(s).append(delim);
						builder.setLength(builder.length() - delim.length());
						arrayList.add(builder.toString());
					}
				}
				adapter = new ArrayAdapter<>(activity, R.layout.spinner_item, arrayList);
				listView.setAdapter(adapter);
			}
		}.run();

		findViewById(R.id.dev_activity_recognition_clear).setOnClickListener((f) -> {
			adapter.clear();
			DataStore.deleteFile(FILE);
		});
	}
}
