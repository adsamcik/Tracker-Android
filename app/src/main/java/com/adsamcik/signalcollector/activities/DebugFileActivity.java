package com.adsamcik.signalcollector.activities;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.adsamcik.signalcollector.file.DataStore;
import com.adsamcik.signalcollector.file.FileStore;

public class DebugFileActivity extends DetailActivity {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		String fileName = getIntent().getStringExtra("fileName");
		String directory = getIntent().getStringExtra("folder");
		setTitle(fileName);
		TextView tv = new TextView(this);
		LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		tv.setLayoutParams(layoutParams);
		tv.setText(FileStore.loadString(FileStore.file(directory, fileName)));
		createScrollableContentParent(true).addView(tv);
	}
}
