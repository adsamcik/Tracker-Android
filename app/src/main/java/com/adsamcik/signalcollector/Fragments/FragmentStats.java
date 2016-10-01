package com.adsamcik.signalcollector.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.ContextCompat;
import android.support.v4.widget.SwipeRefreshLayout;
import android.util.JsonReader;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.adsamcik.signalcollector.Assist;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.Setting;
import com.adsamcik.signalcollector.classes.DataStore;
import com.adsamcik.signalcollector.classes.Network;
import com.adsamcik.signalcollector.classes.Success;
import com.adsamcik.signalcollector.classes.Table;
import com.adsamcik.signalcollector.data.Stat;
import com.adsamcik.signalcollector.data.StatData;
import com.adsamcik.signalcollector.data.StatDay;
import com.adsamcik.signalcollector.interfaces.ITabFragment;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class FragmentStats extends Fragment implements ITabFragment {
	private static final String GENERAL_STAT_FILE = "general_stats_cache_file";
	private static final String USER_STAT_FILE = "user_stats_cache_file";

	private Table weeklyStats;
	private ArrayList<Table> publicStats = null;
	private View view;

	private SwipeRefreshLayout refreshLayout;

	//todo add user stats
	//todo Improve stats updating

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		weeklyStats = new Table(getContext(), 4, false);
	}

	@Override
	public void onDestroyView() {
		((LinearLayout) view.findViewById(R.id.statsLayout)).removeAllViews();
		publicStats.clear();
		super.onDestroyView();
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		view = inflater.inflate(R.layout.fragment_stats, container, false);
		Resources r = getResources();

		Setting.checkStatsDay(getActivity());

		weeklyStats.clear();
		weeklyStats.setTitle(r.getString(R.string.stats_weekly_title));
		StatDay weekStats = Setting.countStats(getActivity());
		weeklyStats.addRow().addData(r.getString(R.string.stats_weekly_minutes), String.valueOf(weekStats.getMinutes()));
		weeklyStats.addRow().addData(r.getString(R.string.stats_weekly_uploaded), Assist.humanReadableByteCount(weekStats.getUploaded()));
		weeklyStats.addRow().addData(r.getString(R.string.stats_weekly_collected_location), String.valueOf(weekStats.getLocations()));
		weeklyStats.addRow().addData(r.getString(R.string.stats_weekly_collected_wifi), String.valueOf(weekStats.getWifi()));
		weeklyStats.addRow().addData(r.getString(R.string.stats_weekly_collected_cell), String.valueOf(weekStats.getCell()));
		weeklyStats.addToViewGroup((LinearLayout) view.findViewById(R.id.statsLayout), 0, false, 0);
		GetPublicStats();

		refreshLayout = (SwipeRefreshLayout) view.findViewById(R.id.statsSwipeRefresh);
		refreshLayout.setOnRefreshListener(this::GetPublicStats);
		Context c = getContext();
		refreshLayout.setColorSchemeResources(R.color.colorPrimary);
		return view;
	}

	private void GetPublicStats() {
		OkHttpClient client = new OkHttpClient();
		Request request = new Request.Builder()
				.url(Network.URL_STATS)
				.build();
		Callback c = new Callback() {
			@Override
			public void onFailure(Call call, IOException e) {

			}

			@Override
			public void onResponse(Call call, Response response) throws IOException {
				String body = response.body().string();
				response.close();
				if (body.startsWith("[")) {
					DataStore.saveString(GENERAL_STAT_FILE, body);
					Activity activity = getActivity();
					List<Stat> stats = new Gson().fromJson(body, new TypeToken<List<Stat>>() {}.getType());
					if(publicStats != null) {
						for(Table t : publicStats) {
							t.destroy(activity);
						}
					}
					activity.runOnUiThread(() -> publicStats = GenerateStatsTable(stats));
					if (refreshLayout != null)
						activity.runOnUiThread(() -> refreshLayout.setRefreshing(false));
				}
			}
		};
		client.newCall(request).enqueue(c);
	}

	private void GetUserStats() {
		OkHttpClient client = new OkHttpClient();
		Request request = new Request.Builder()
				.url(Network.URL_USER_STATS)
				.build();
		Callback c = new Callback() {
			@Override
			public void onFailure(Call call, IOException e) {

			}

			@Override
			public void onResponse(Call call, Response response) throws IOException {
				String body = response.body().string();
				if (body.startsWith("[")) {
					List<Stat> stats = new Gson().fromJson(body, new TypeToken<List<Stat>>() {}.getType());
					getActivity().runOnUiThread(() -> GenerateStatsTable(stats));
				}
				response.close();
			}
		};
		client.newCall(request).enqueue(c);
	}

	/**
	 * Generates table from List of stats
	 *
	 * @param stats list of stats
	 */
	private ArrayList<Table> GenerateStatsTable(List<Stat> stats) {
		ArrayList<Table> items = new ArrayList<>();
		Context c = getContext();
		LinearLayout ll = (LinearLayout) view.findViewById(R.id.statsLayout);
		for (int i = 0; i < stats.size(); i++) {
			Stat s = stats.get(i);
			if (s.data != null) {
				Table table = new Table(c, s.data.size(), s.showPosition);
				table.setTitle(s.name);
				for (int y = 0; y < s.data.size(); y++) {
					StatData sd = s.data.get(y);
					table.addRow().addData(sd.id, sd.value);
				}
				table.addToViewGroup(ll, true, (i + 1) * 150);
				items.add(table);
			}
		}
		return items;
	}

	@Override
	public Success<String> onEnter(FragmentActivity activity, FloatingActionButton fabOne, FloatingActionButton fabTwo) {
		//todo check if up to date
		return new Success<>();
	}

	@Override
	public void onLeave() {

	}

	@Override
	public void onPermissionResponse(int requestCode, boolean success) {

	}

	@Override
	public ITabFragment newInstance() {
		return new FragmentStats();
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

