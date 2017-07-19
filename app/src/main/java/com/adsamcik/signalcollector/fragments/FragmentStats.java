package com.adsamcik.signalcollector.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.ContextCompat;
import android.support.v4.widget.SwipeRefreshLayout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Failure;
import com.adsamcik.signalcollector.network.NetworkLoader;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.activities.RecentUploadsActivity;
import com.adsamcik.signalcollector.utility.DataStore;
import com.adsamcik.signalcollector.network.Network;
import com.adsamcik.signalcollector.network.Signin;
import com.adsamcik.signalcollector.utility.SnackMaker;
import com.adsamcik.signalcollector.utility.Table;
import com.adsamcik.signalcollector.data.UploadStats;
import com.adsamcik.signalcollector.data.Stat;
import com.adsamcik.signalcollector.data.StatData;
import com.adsamcik.signalcollector.data.StatDay;
import com.adsamcik.signalcollector.interfaces.ITabFragment;

import java.util.ArrayList;

public class FragmentStats extends Fragment implements ITabFragment {
	private Table weeklyStats;
	private Table lastUpload;
	private final ArrayList<Table> publicStats = new ArrayList<>();
	private final ArrayList<Table> userStats = new ArrayList<>();
	private View view;

	private SwipeRefreshLayout refreshLayout;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Context context = getContext();
		weeklyStats = new Table(context, 4, false, ContextCompat.getColor(context, R.color.textPrimary));
	}

	@Override
	public void onDestroyView() {
		((LinearLayout) view.findViewById(R.id.statsLayout)).removeAllViews();
		publicStats.clear();
		userStats.clear();
		super.onDestroyView();
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		view = inflater.inflate(R.layout.fragment_stats, container, false);
		Resources r = getResources();

		boolean hasRecentUpload = false;

		Activity activity = getActivity();
		DataStore.setContext(getContext());

		UploadStats us = DataStore.loadLastObjectJsonArrayAppend(DataStore.RECENT_UPLOADS_FILE, UploadStats.class);
		if (us != null && Assist.getAgeInDays(us.time) < 30) {
			lastUpload = RecentUploadsActivity.GenerateTableForUploadStat(us, view.findViewById(R.id.statsLayout), getContext(), getResources().getString(R.string.most_recent_upload));
			lastUpload.addButton(getString(R.string.more_uploads), v -> {
				Intent intent = new Intent(getContext(), RecentUploadsActivity.class);
				startActivity(intent);
			});
			hasRecentUpload = true;
		} else {
			if (lastUpload != null)
				lastUpload.destroy(activity);
		}

		new Thread(DataStore::removeOldRecentUploads).start();

		Preferences.checkStatsDay(activity);

		weeklyStats.clear();
		weeklyStats.addTitle(r.getString(R.string.stats_weekly_title));
		StatDay weekStats = Preferences.countStats(activity);
		weeklyStats.addRow().addData(r.getString(R.string.stats_weekly_minutes), String.valueOf(weekStats.getMinutes()));
		weeklyStats.addRow().addData(r.getString(R.string.stats_weekly_uploaded), Assist.humanReadableByteCount(weekStats.getUploaded(), true));
		weeklyStats.addRow().addData(r.getString(R.string.stats_weekly_collected_location), String.valueOf(weekStats.getLocations()));
		weeklyStats.addRow().addData(r.getString(R.string.stats_weekly_collected_wifi), String.valueOf(weekStats.getWifi()));
		weeklyStats.addRow().addData(r.getString(R.string.stats_weekly_collected_cell), String.valueOf(weekStats.getCell()));
		weeklyStats.addToViewGroup(view.findViewById(R.id.statsLayout), hasRecentUpload ? 1 : 0, false, 0);

		refreshLayout = view.findViewById(R.id.statsSwipeRefresh);
		refreshLayout.setOnRefreshListener(this::updateStats);
		refreshLayout.setColorSchemeResources(R.color.colorPrimary);
		refreshLayout.setProgressViewOffset(true, 0, Assist.dpToPx(activity, 40));
		updateStats();
		return view;
	}

	private void updateStats() {
		Activity activity = getActivity();
		final boolean isRefresh = refreshLayout != null && refreshLayout.isRefreshing();
		refreshingCount++;
		refreshLayout.setRefreshing(true);
		NetworkLoader.request(Network.URL_STATS, isRefresh ? 0 : Assist.DAY_IN_MINUTES, getContext(), Preferences.PREF_GENERAL_STATS, Stat[].class, (state, value) -> {
			refreshDone();
			final int initialIndex = ((ViewGroup) view).getChildCount();

			if (state.isSuccess())
				generateStats(value, publicStats, initialIndex, activity);
			else {
				generateStats(value, publicStats, initialIndex, activity);
				new SnackMaker(activity).showSnackbar(state.toString(activity));
			}
		});
		refreshingCount++;
		Signin.getUserAsync(activity, user -> {
			Context thisContext = getContext();
			if(user != null && thisContext != null) {
				final Context appContext = thisContext.getApplicationContext();
				NetworkLoader.request(Network.URL_USER_STATS, isRefresh ? 0 : Assist.DAY_IN_MINUTES, appContext, Preferences.PREF_USER_STATS, Stat[].class, (state, value) -> {
					refreshDone();
					if (value != null) {
						final int initialIndex = 1 + (lastUpload == null ? 0 : 1);
						if (state.isSuccess()) {
							Resources r = appContext.getResources();
							if (value.length == 1 && value[0].name.isEmpty())
								value[0] = new Stat(r.getString(R.string.your_stats), value[0].type, value[0].showPosition, value[0].data);
							generateStats(value, userStats, initialIndex, activity);
						} else {
							generateStats(value, userStats, initialIndex, activity);
							new SnackMaker(activity).showSnackbar(state.toString(activity));
						}
					}
				});
			} else
				refreshDone();
		});
	}

	private int refreshingCount = 0;

	private void refreshDone() {
		if (--refreshingCount == 0)
			if (refreshLayout != null && refreshLayout.isRefreshing()) {
				Activity activity = getActivity();
				if (activity != null)
					activity.runOnUiThread(() -> refreshLayout.setRefreshing(false));
			}
	}

	private void generateStats(Stat[] stats, ArrayList<Table> items, int insertAt, Activity activity) {
		if (stats == null)
			return;

		if (items != null) {
			for (Table t : items) {
				t.destroy(activity);
			}
		}
		activity.runOnUiThread(() -> generateStatsTable(stats, insertAt, items, activity));
	}

	/**
	 * Generates tables from list of stats
	 *
	 * @param stats stats
	 * @param items array to which items will be added
	 * @return returns passed items array
	 */
	private ArrayList<Table> generateStatsTable(Stat[] stats, int insertAt, ArrayList<Table> items, Context context) {
		if (context == null)
			return null;

		LinearLayout ll = view.findViewById(R.id.statsLayout);
		int color = ContextCompat.getColor(context, R.color.textPrimary);
		for (int i = 0; i < stats.length; i++) {
			Stat s = stats[i];
			if (s.data != null) {
				Table table = new Table(context, s.data.size(), s.showPosition, color);
				table.addTitle(s.name);
				for (int y = 0; y < s.data.size(); y++) {
					StatData sd = s.data.get(y);
					table.addRow().addData(sd.id, sd.value);
				}
				table.addToViewGroup(ll, insertAt + i, true, (i + 1) * 150);
				items.add(table);
			}
		}
		return items;
	}

	@NonNull
	@Override
	public Failure<String> onEnter(@NonNull FragmentActivity activity, @NonNull FloatingActionButton fabOne, @NonNull FloatingActionButton fabTwo) {
		//todo check if up to date
		return new Failure<>();
	}

	@Override
	public void onLeave(@NonNull FragmentActivity activity) {
		if (refreshLayout != null && refreshLayout.isRefreshing())
			refreshLayout.setRefreshing(false);
	}

	@Override
	public void onPermissionResponse(int requestCode, boolean success) {

	}

	@Override
	public void onHomeAction() {
		if (view != null)
			Assist.verticalSmoothScrollTo((ScrollView) view.findViewById(R.id.statsLayout).getParent(), 0, 500);
	}

}

