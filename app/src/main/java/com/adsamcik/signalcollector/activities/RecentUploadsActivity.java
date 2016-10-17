package com.adsamcik.signalcollector.activities;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.NavUtils;
import android.support.v4.content.ContextCompat;
import android.text.format.DateFormat;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.utility.DataStore;
import com.adsamcik.signalcollector.utility.Table;
import com.adsamcik.signalcollector.data.UploadStats;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class RecentUploadsActivity extends Activity {

	public static Table GenerateTableForUploadStat(@NonNull UploadStats uploadStat, ViewGroup parent, @NonNull Context context) {
		Resources resources = context.getResources();
		Table t = new Table(context, 4, false, ContextCompat.getColor(context, R.color.textPrimary));
		t.addTitle(DateFormat.format("dd.MM hh:mm", new Date(uploadStat.time)).toString());
		t.addRow().addData(resources.getString(R.string.recent_upload_size), Assist.humanReadableByteCount(uploadStat.uploadSize));
		t.addRow().addData(resources.getString(R.string.recent_upload_collections), String.valueOf(uploadStat.collections));
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
		setContentView(R.layout.activity_recent_uploads);

		ArrayList<UploadStats> recent = new Gson().fromJson(DataStore.loadJsonArrayAppend(DataStore.RECENT_UPLOADS_FILE), new TypeToken<List<UploadStats>>() {
		}.getType());
		Context context = getApplicationContext();
		for (UploadStats s : recent) {
			GenerateTableForUploadStat(s, (LinearLayout) findViewById(R.id.recent_uploads_layout), context);
		}

		findViewById(R.id.back_button).setOnClickListener(view -> NavUtils.navigateUpFromSameTask(this));
	}
}
