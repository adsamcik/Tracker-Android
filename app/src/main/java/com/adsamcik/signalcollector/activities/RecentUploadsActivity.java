package com.adsamcik.signalcollector.activities;

import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TableLayout;

import com.adsamcik.signalcollector.adapters.TableAdapter;
import com.adsamcik.signalcollector.enums.AppendBehavior;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.file.DataStore;
import com.adsamcik.signalcollector.utility.Table;
import com.adsamcik.signalcollector.data.UploadStats;
import com.google.gson.Gson;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class RecentUploadsActivity extends DetailActivity {

	/**
	 * Function for generating table for upload stats
	 *
	 * @param uploadStat upload stat
	 * @param context    context
	 * @param title      title, if null is replaced with upload time
	 * @return table
	 */
	public static Table GenerateTableForUploadStat(@NonNull UploadStats uploadStat, @NonNull Context context, @Nullable String title, @NonNull AppendBehavior appendBehavior) {
		Resources resources = context.getResources();
		Table t = new Table(9, false, ContextCompat.getColor(context, R.color.text_primary), 16, appendBehavior);


		if (title == null) {
			DateFormat dateFormat = android.text.format.DateFormat.getDateFormat(context);
			DateFormat timeFormat = android.text.format.DateFormat.getTimeFormat(context);
			Date dateTime = new Date(uploadStat.time);
			t.setTitle(dateFormat.format(dateTime) + " " + timeFormat.format(dateTime));
		} else
			t.setTitle(title);

		t.addData(resources.getString(R.string.recent_upload_size), Assist.humanReadableByteCount(uploadStat.uploadSize, true));
		t.addData(resources.getString(R.string.recent_upload_collections), String.valueOf(uploadStat.collections));
		t.addData(resources.getString(R.string.recent_upload_locations_new), String.valueOf(uploadStat.newLocations));
		t.addData(resources.getString(R.string.recent_upload_wifi), String.valueOf(uploadStat.wifi));
		t.addData(resources.getString(R.string.recent_upload_wifi_new), String.valueOf(uploadStat.newWifi));
		t.addData(resources.getString(R.string.recent_upload_cell), String.valueOf(uploadStat.cell));
		t.addData(resources.getString(R.string.recent_upload_cell_new), String.valueOf(uploadStat.newCell));
		t.addData(resources.getString(R.string.recent_upload_noise), String.valueOf(uploadStat.noiseCollections));
		t.addData(resources.getString(R.string.recent_upload_noise_new), String.valueOf(uploadStat.newNoiseLocations));
		return t;
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setTitle(R.string.recent_uploads);

		UploadStats[] recent = new Gson().fromJson(DataStore.loadAppendableJsonArray(this, DataStore.RECENT_UPLOADS_FILE), UploadStats[].class);
		if (recent != null && recent.length > 0) {
			Context context = getApplicationContext();
			LinearLayout parent = createContentParent(false);
			ListView listView = new ListView(this);
			listView.setDivider(null);
			listView.setDividerHeight(0);
			listView.setSelector(android.R.color.transparent);
			TableAdapter adapter = new TableAdapter(context, 16);

			for (UploadStats s : recent)
				adapter.add(GenerateTableForUploadStat(s, context, null, AppendBehavior.Any));

			listView.setAdapter(adapter);
			parent.addView(listView);
		}


		//if (recent.size() > 10)
		//	DataStore.saveString(DataStore.RECENT_UPLOADS_FILE, new Gson().toJson(recent.subList(recent.size() - 11, recent.size() - 1)));
	}
}
