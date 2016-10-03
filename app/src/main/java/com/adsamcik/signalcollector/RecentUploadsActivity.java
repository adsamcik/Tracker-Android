package com.adsamcik.signalcollector;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.NavUtils;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.LinearLayout;

import com.adsamcik.signalcollector.classes.DataStore;
import com.adsamcik.signalcollector.classes.Table;
import com.adsamcik.signalcollector.classes.UploadStats;
import com.adsamcik.signalcollector.data.Stat;
import com.adsamcik.signalcollector.data.StatDay;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class RecentUploadsActivity extends Activity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_recent_uploads);

		ArrayList<UploadStats> recent = new Gson().fromJson(DataStore.loadJsonArrayAppend(DataStore.RECENT_UPLOADS_FILE), new TypeToken<List<Stat>>() {
		}.getType());
		for (UploadStats s : recent) {
			Table t = new Table(this, 4, false);
			t.setTitle(DateFormat.format("hh:mm dd.MM", new Date(s.time)).toString());
			t.addRow().addData("Size", Assist.humanReadableByteCount(s.uploadSize));
			t.addRow().addData("New locations", String.valueOf(s.collections));
			t.addRow().addData("Wifi found", s.wifi + " (" + s.newWifi + " new)");
			t.addRow().addData("Cell found", s.cell + " (" + s.newCell + " new)");
			t.addRow().addData("Noise collected", String.valueOf(s.noiseCollections));
			t.addToViewGroup((LinearLayout) findViewById(R.id.recent_uploads_layout), 0, false, 0);
		}

		findViewById(R.id.back_button).setOnClickListener(view -> NavUtils.navigateUpFromSameTask(this));
	}
}
