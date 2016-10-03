package com.adsamcik.signalcollector;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.NavUtils;
import android.view.View;
import android.widget.LinearLayout;

import com.adsamcik.signalcollector.classes.Table;
import com.adsamcik.signalcollector.data.StatDay;

public class RecentUploadsActivity extends Activity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_recent_uploads);

		Table t = new Table(this, 4, false);
		t.setTitle("Last upload");
		t.addRow().addData("Wifi found", "50 (33 new)");
		t.addRow().addData("Cell found", "5 (8 new)");
		t.addRow().addData("Noise collected", "50");
		t.getLayout().setTransitionName("lastUpload");
		t.addToViewGroup((LinearLayout) findViewById(R.id.recent_uploads_layout), 0, false, 0);
		findViewById(R.id.back_button).setOnClickListener(view -> NavUtils.navigateUpFromSameTask(this));
	}
}
