package com.adsamcik.signalcollector.activities;

import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TableLayout;

import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.utility.DataStore;
import com.adsamcik.signalcollector.utility.Table;
import com.adsamcik.signalcollector.data.UploadStats;
import com.google.gson.Gson;

import java.text.DateFormat;
import java.util.Date;

public class RecentUploadsActivity extends DetailActivity {

	/**
	 * Function for generating table for upload stats
	 *
	 * @param uploadStat upload stat
	 * @param parent     parent view
	 * @param context    context
	 * @param title      title, if null is replaced with upload time
	 * @return table
	 */
	public static Table GenerateTableForUploadStat(@NonNull UploadStats uploadStat, ViewGroup parent, @NonNull Context context, @Nullable String title) {
		Resources resources = context.getResources();
		Table t = new Table(context, 9, false, ContextCompat.getColor(context, R.color.textPrimary));


		if (title == null) {
			DateFormat dateFormat = android.text.format.DateFormat.getDateFormat(context);
			DateFormat timeFormat = android.text.format.DateFormat.getTimeFormat(context);
			Date dateTime = new Date(uploadStat.time);
			t.addTitle(dateFormat.format(dateTime) + " " + timeFormat.format(dateTime));
		} else
			t.addTitle(title);

		t.addRow().addData(resources.getString(R.string.recent_upload_size), Assist.humanReadableByteCount(uploadStat.uploadSize, true));
		t.addRow().addData(resources.getString(R.string.recent_upload_collections), String.valueOf(uploadStat.collections));
		t.addRow().addData(resources.getString(R.string.recent_upload_locations_new), String.valueOf(uploadStat.newLocations));
		t.addRow().addData(resources.getString(R.string.recent_upload_wifi), String.valueOf(uploadStat.wifi));
		t.addRow().addData(resources.getString(R.string.recent_upload_wifi_new), String.valueOf(uploadStat.newWifi));
		t.addRow().addData(resources.getString(R.string.recent_upload_cell), String.valueOf(uploadStat.cell));
		t.addRow().addData(resources.getString(R.string.recent_upload_cell_new), String.valueOf(uploadStat.newCell));
		t.addRow().addData(resources.getString(R.string.recent_upload_noise), String.valueOf(uploadStat.noiseCollections));
		t.addRow().addData(resources.getString(R.string.recent_upload_noise_new), String.valueOf(uploadStat.newNoiseLocations));
		t.addToViewGroup(parent, 0, false, 0);
		return t;
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		DataStore.setContext(this);
		setTitle(R.string.recent_uploads);

		UploadStats[] recent = new Gson().fromJson(DataStore.loadJsonArrayAppend(DataStore.RECENT_UPLOADS_FILE), UploadStats[].class);
		if (recent != null && recent.length > 0) {
			Context context = getApplicationContext();
			LinearLayout parent = createScrollableContentParent(true);
			for (UploadStats s : recent)
				GenerateTableForUploadStat(s, parent, context, null);
			parent.getChildAt(0).setLayoutParams(new TableLayout.LayoutParams());
		}


		//if (recent.size() > 10)
		//	DataStore.saveString(DataStore.RECENT_UPLOADS_FILE, new Gson().toJson(recent.subList(recent.size() - 11, recent.size() - 1)));
	}
}
