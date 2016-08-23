package com.adsamcik.signalcollector.fragments;

import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.util.JsonReader;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.adsamcik.signalcollector.classes.DataStore;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.Setting;
import com.adsamcik.signalcollector.classes.Success;
import com.adsamcik.signalcollector.classes.Table;
import com.adsamcik.signalcollector.data.Stat;
import com.adsamcik.signalcollector.data.StatData;
import com.adsamcik.signalcollector.data.StatDay;
import com.adsamcik.signalcollector.interfaces.ITabFragment;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class FragmentStats extends Fragment implements ITabFragment {
	private static final String GENERAL_STAT_FILE = "general_stats_cache_file";
	private static final String USER_STAT_FILE = "user_stats_cache_file";
	private static long lastRequest = 0;

	private Table weeklyStats;
	private View view;

	//todo add user stats
	//todo add last day stats

	//todo Improve stats updating

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		weeklyStats = new Table(getContext(), 4, false);
	}

	@Override
	public void onDestroyView() {
		((LinearLayout) view.findViewById(R.id.statsLayout)).removeAllViews();
		super.onDestroyView();
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		view = inflater.inflate(R.layout.fragment_stats, container, false);
		((LinearLayout) view.findViewById(R.id.statsLayout)).addView(weeklyStats.getLayout(), 0);
		Resources r = getResources();

		Setting.checkStatsDay(getActivity());

		weeklyStats.clear();
		weeklyStats.setTitle(r.getString(R.string.stats_weekly_title));
		StatDay weekStats = Setting.countStats(getActivity());
		weeklyStats.addRow().addData(r.getString(R.string.stats_weekly_minutes), String.valueOf(weekStats.getMinutes()));
		weeklyStats.addRow().addData(r.getString(R.string.stats_weekly_collected_location), String.valueOf(weekStats.getLocations()));
		weeklyStats.addRow().addData(r.getString(R.string.stats_weekly_collected_wifi), String.valueOf(weekStats.getWifi()));
		weeklyStats.addRow().addData(r.getString(R.string.stats_weekly_collected_cell), String.valueOf(weekStats.getCell()));
		return view;
	}

	@Override
	public void onStart() {
		super.onStart();
		long time = System.currentTimeMillis();
		time -= time % 600000;
		time += 120000;
		if (DataStore.exists(GENERAL_STAT_FILE)) {
			DataStore.deleteFile(GENERAL_STAT_FILE);
		}
	}

	/**
	 * Generates table from List of stats
	 * @param stats list of stats
	 */
	private void GenerateStatsTable(List<Stat> stats) {
		Context c = getContext();
		LinearLayout ll = (LinearLayout) view.findViewById(R.id.statsLayout);
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
	public Success<String> onEnter(FragmentActivity activity, FloatingActionButton fabOne, FloatingActionButton fabTwo) {
		//todo check if up to date
		fabOne.hide();
		fabTwo.hide();

		return new Success<>();
	}

	@Override
	public void onLeave() {

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

