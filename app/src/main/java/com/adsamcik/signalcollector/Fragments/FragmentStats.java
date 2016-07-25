package com.adsamcik.signalcollector.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.util.JsonReader;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.adsamcik.signalcollector.DataStore;
import com.adsamcik.signalcollector.Network;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.Setting;
import com.adsamcik.signalcollector.Table;
import com.adsamcik.signalcollector.data.Stat;
import com.adsamcik.signalcollector.data.StatData;
import com.adsamcik.signalcollector.interfaces.ITabFragment;
import com.adsamcik.signalcollector.play.PlayController;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.client.cache.Resource;

public class FragmentStats extends Fragment implements ITabFragment {
	private static final String GENERAL_STAT_FILE = "general_stats_cache_file";
	private static final String USER_STAT_FILE = "user_stats_cache_file";
	private static int lastIndex = -1;
	private static long lastRequest = 0;
	private final AsyncHttpClient client = new AsyncHttpClient();
	private View v;

	//todo add user stats
	//todo add last day stats

	//todo Improve stats updating
	private final AsyncHttpResponseHandler generalStatsResponseHandler = new AsyncHttpResponseHandler() {
		@Override
		public void onSuccess(int statusCode, Header[] headers, byte[] responseBody) {
			if (responseBody != null && responseBody.length > 0)
				try {
					String data = new String(responseBody);
					DataStore.saveString(GENERAL_STAT_FILE, data);
					GenerateStatsTable(readJsonStream(new ByteArrayInputStream(responseBody)));
				} catch (IOException e) {
					Log.e("Error", e.getMessage());
				}
		}

		@Override
		public void onFailure(int statusCode, Header[] headers, byte[] responseBody, Throwable error) {

		}
	};

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_stats, container, false);

		//todo show notification when offline to let know that the stats might be outdated
		v = view;
		long time = System.currentTimeMillis();
		time -= time % 600000;
		time += 120000;
		long diff = time - lastRequest;

		SharedPreferences sp = Setting.getPreferences(getContext());
		Table table = new Table(getContext(), 4, false);
		table.setTitle("Today");
		table.addRow().addData("Seen wifi", String.valueOf(sp.getInt(Setting.STATS_WIFI_FOUND, 0)));
		table.addRow().addData("Seen cell", String.valueOf(sp.getInt(Setting.STATS_CELL_FOUND, 0)));
		table.addRow().addData("Tracking count", String.valueOf(sp.getInt(Setting.STATS_CELL_FOUND, 0)));
		((LinearLayout) v.findViewById(R.id.statsLayout)).addView(table.getLayout());

		//todo show local device stats
		if (diff > 600000) {
			client.get(Network.URL_STATS, null, generalStatsResponseHandler);
			lastRequest = time;
		} else {
			String data;
			if (DataStore.exists(GENERAL_STAT_FILE)) {
				data = DataStore.loadString(GENERAL_STAT_FILE);
				try {
					GenerateStatsTable(readJsonStream(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8))));
				} catch (IOException e) {
					Log.e("Error", e.getMessage());
				}
			}
		}
		return view;
	}

	private void GenerateStatsTable(List<Stat> stats) {
		Context c = getContext();
		Resources r = getResources();
		LinearLayout ll = (LinearLayout) v.findViewById(R.id.statsLayout);
		for (int i = 0; i < stats.size(); i++) {
			Stat s = stats.get(i);
			Table table = new Table(c, s.statData.size(), s.showPosition);
			table.setTitle(s.name);
			for (int y = 0; y < s.statData.size(); y++) {
				StatData sd = s.statData.get(y);
				table.addRow().addData(sd.id, sd.value);
			}
			ll.addView(table.getLayout());
		}
	}

	@Override
	public boolean onEnter(Activity activity, FloatingActionButton fabOne, FloatingActionButton fabTwo) {
		//todo check if up to date
		fabOne.hide();
		fabTwo.hide();
		return true;
	}

	@Override
	public void onLeave() {

	}

	private List<Stat> readJsonStream(InputStream in) throws IOException {
		try (JsonReader reader = new JsonReader(new InputStreamReader(in, "UTF-8"))) {
			return readStatDataArray(reader);
		}
	}

	private List<Stat> readStatDataArray(JsonReader reader) throws IOException {
		List<Stat> l = new ArrayList<>();
		reader.beginArray();
		while (reader.hasNext())
			l.add(new Stat(reader));
		reader.endArray();
		return l;
	}
}

