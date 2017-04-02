package com.adsamcik.signalcollector.activities;

import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.app.NavUtils;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.utility.DataStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DebugFileActivity extends DetailActivity {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		String fileName = getIntent().getStringExtra("fileName");
		setTitle(fileName);
		DataStore.setContext(getApplicationContext());
		TextView tv = new TextView(this);
		LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		tv.setLayoutParams(layoutParams);
		tv.setText(DataStore.loadString(fileName));
		getLayout().addView(tv);
	}
}
