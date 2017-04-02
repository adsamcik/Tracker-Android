package com.adsamcik.signalcollector.activities;

import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.TextView;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.utility.DataStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DebugFileActivity extends AppCompatActivity {

	public static String toPrettyFormat(String jsonString)
	{
		JsonParser parser = new JsonParser();
		JsonObject json = parser.parse(jsonString).getAsJsonObject();

		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		String prettyJson = gson.toJson(json);

		return prettyJson;
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_debug_file);
		Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
		String fileName = getIntent().getStringExtra("fileName");
		toolbar.setTitle(fileName);
		setSupportActionBar(toolbar);
		DataStore.setContext(getApplicationContext());
		((TextView)findViewById(R.id.dev_content_text)).setText(DataStore.loadString(fileName));
	}
}
