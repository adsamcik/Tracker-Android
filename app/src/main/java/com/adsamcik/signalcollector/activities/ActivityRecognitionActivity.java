package com.adsamcik.signalcollector.activities;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.utility.DataStore;
import com.adsamcik.signalcollector.utility.Parser;
import com.adsamcik.signalcollector.utility.Preferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import static java.text.DateFormat.getDateInstance;

public class ActivityRecognitionActivity extends DetailActivity {
	private static final String FILE = "activityRecognitionDebug.tsv";

	private Button startStopButton;
	private ArrayList<String> arrayList;
	private ArrayAdapter<String> adapter;

	public static void addLineIfDebug(String activity, String action, @NonNull Context context) {
		if(Preferences.get(context).getBoolean(Preferences.PREF_DEV_ACTIVITY_TRACKING_ENABLED, false))
			addLine(activity, action);
	}

	private static void addLine(String activity, String action) {
		DataStore.saveStringAppend(FILE, getDateInstance().format(System.currentTimeMillis()) + '\t' + activity + '\t' + action + '\n');
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		View v = getLayoutInflater().inflate(R.layout.layout_activity_recognition, createContentParent(false));
		startStopButton = findViewById(R.id.dev_activity_debug_start_stop_button);

		setTitle(R.string.dev_activity_recognition_title);

		final ListView listView = v.findViewById(R.id.dev_activity_list_view);


		startStopButton.setOnClickListener(view -> {
			SharedPreferences sp = Preferences.get(this);
			boolean setEnabled = !sp.getBoolean(Preferences.PREF_DEV_ACTIVITY_TRACKING_ENABLED, false);
			sp.edit().putBoolean(Preferences.PREF_DEV_ACTIVITY_TRACKING_ENABLED, setEnabled).apply();
			if (setEnabled)
				startStopButton.setText(getString(R.string.stop));
			else
				startStopButton.setText(getString(R.string.start));
		});

		final Activity activity = this;

		new Thread() {
			@Override
			public void run() {
				ArrayList<String[]> items = Parser.parseTSVFromFile(activity, FILE);
				if (items == null)
					return;

				final String delim = ", ";
				arrayList = new ArrayList<>(items.size());
				activity.runOnUiThread(() -> {
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
				});
			}
		}.run();

		findViewById(R.id.dev_activity_recognition_clear).setOnClickListener((f) -> {
			adapter.clear();
			DataStore.deleteFile(FILE);
		});
	}
}
