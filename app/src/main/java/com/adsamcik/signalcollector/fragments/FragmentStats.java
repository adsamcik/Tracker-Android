package com.adsamcik.signalcollector.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
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
import android.widget.ListView;

import com.adsamcik.signalcollector.adapters.TableAdapter;
import com.adsamcik.signalcollector.enums.AppendBehavior;
import com.adsamcik.signalcollector.network.Signin;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Failure;
import com.adsamcik.signalcollector.network.NetworkLoader;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.activities.UploadReportsActivity;
import com.adsamcik.signalcollector.file.DataStore;
import com.adsamcik.signalcollector.network.Network;
import com.adsamcik.signalcollector.utility.SnackMaker;
import com.adsamcik.signalcollector.utility.Table;
import com.adsamcik.signalcollector.data.UploadStats;
import com.adsamcik.signalcollector.data.Stat;
import com.adsamcik.signalcollector.data.StatData;
import com.adsamcik.signalcollector.data.StatDay;
import com.adsamcik.signalcollector.interfaces.ITabFragment;

public class FragmentStats extends Fragment implements ITabFragment {
	private View view;

	private TableAdapter adapter;

	private SwipeRefreshLayout refreshLayout;

	private final int CARD_LIST_MARGIN = 16;

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		view = inflater.inflate(R.layout.fragment_stats, container, false);

		Activity activity = getActivity();

		if(adapter == null)
			adapter = new TableAdapter(activity, CARD_LIST_MARGIN);

		new Thread(() -> DataStore.removeOldRecentUploads(activity)).start();

		Preferences.checkStatsDay(activity);

		//weeklyStats.addToViewGroup(view.findViewById(R.id.statsLayout), hasRecentUpload ? 1 : 0, false, 0);

		refreshLayout = view.findViewById(R.id.statsSwipeRefresh);
		refreshLayout.setOnRefreshListener(this::updateStats);
		refreshLayout.setColorSchemeResources(R.color.color_primary);
		refreshLayout.setProgressViewOffset(true, 0, Assist.dpToPx(activity, 40));

		ListView listView = view.findViewById(R.id.stats_list_view);
		listView.setAdapter(adapter);
		updateStats();
		return view;
	}

	private void updateStats() {
		Activity activity = getActivity();
		final Context appContext = activity.getApplicationContext();
		assert refreshLayout != null;
		final boolean isRefresh = refreshLayout.isRefreshing();

		adapter.clear();

		Resources r = activity.getResources();

		UploadStats us = DataStore.loadLastFromAppendableJsonArray(activity, DataStore.RECENT_UPLOADS_FILE, UploadStats.class);
		if (us != null && Assist.getAgeInDays(us.time) < 30) {
			Table lastUpload = UploadReportsActivity.GenerateTableForUploadStat(us, getContext(), getResources().getString(R.string.most_recent_upload), AppendBehavior.FirstFirst);
			lastUpload.addButton(getString(R.string.more_uploads), v -> {
				Intent intent = new Intent(getContext(), UploadReportsActivity.class);
				startActivity(intent);
			});
			adapter.add(lastUpload);
		}

		Table weeklyStats = new Table(4, false, ContextCompat.getColor(activity, R.color.text_primary), CARD_LIST_MARGIN, AppendBehavior.FirstFirst);
		weeklyStats.setTitle(r.getString(R.string.stats_weekly_title));
		StatDay weekStats = Preferences.countStats(activity);
		weeklyStats.addData(r.getString(R.string.stats_weekly_minutes), String.valueOf(weekStats.getMinutes()));
		weeklyStats.addData(r.getString(R.string.stats_weekly_uploaded), Assist.humanReadableByteCount(weekStats.getUploaded(), true));
		weeklyStats.addData(r.getString(R.string.stats_weekly_collected_location), String.valueOf(weekStats.getLocations()));
		weeklyStats.addData(r.getString(R.string.stats_weekly_collected_wifi), String.valueOf(weekStats.getWifi()));
		weeklyStats.addData(r.getString(R.string.stats_weekly_collected_cell), String.valueOf(weekStats.getCell()));
		adapter.add(weeklyStats);

		refreshingCount = 2;
		new Handler().postDelayed(() -> {
			if(refreshingCount > 0)
				activity.runOnUiThread(() -> refreshLayout.setRefreshing(true));
		}, 100);

		NetworkLoader.request(Network.URL_GENERAL_STATS, isRefresh ? 0 : Assist.DAY_IN_MINUTES, getContext(), Preferences.PREF_GENERAL_STATS, Stat[].class, (state, value) ->
				handleResponse(activity, state, value, AppendBehavior.FirstLast));

		NetworkLoader.request(Network.URL_STATS, isRefresh ? 0 : Assist.DAY_IN_MINUTES, getContext(), Preferences.PREF_STATS, Stat[].class, (state, value) ->
				handleResponse(activity, state, value, AppendBehavior.Any));

		if (Signin.getUserID(appContext) != null) {
			refreshingCount++;
			NetworkLoader.requestSigned(Network.URL_USER_STATS, isRefresh ? 0 : Assist.DAY_IN_MINUTES, appContext, Preferences.PREF_USER_STATS, Stat[].class, (state, value) -> {
				if (value != null && value.length == 1 && value[0].name.isEmpty())
					value[0] = new Stat(appContext.getString(R.string.your_stats), value[0].type, value[0].showPosition, value[0].data);
				handleResponse(activity, state, value, AppendBehavior.First);
			});
		}
	}

	private void handleResponse(@NonNull Activity activity, @NonNull NetworkLoader.Source state, @Nullable Stat[] value, @NonNull AppendBehavior appendBehavior) {
		if (!state.isSuccess())
			new SnackMaker(activity).showSnackbar(state.toString(activity));
		refreshingCount--;
		if (state.isDataAvailable())
			activity.runOnUiThread(() -> {
				//noinspection ConstantConditions
				addStatsTable(activity, value, appendBehavior);
				adapter.sort();
				if (refreshingCount == 0 && refreshLayout != null)
					refreshLayout.setRefreshing(false);
			});
	}

	private int refreshingCount = 0;

	/**
	 * Generates tables from list of stats
	 *
	 * @param stats stats
	 */
	private void addStatsTable(@NonNull Context context, @NonNull Stat[] stats, @NonNull AppendBehavior appendBehavior) {
		int color = ContextCompat.getColor(context, R.color.text_primary);
		for (Stat s : stats) {
			if (s.data != null) {
				Table table = new Table(s.data.size(), s.showPosition, color, CARD_LIST_MARGIN, appendBehavior);
				table.setTitle(s.name);
				for (int y = 0; y < s.data.size(); y++) {
					StatData sd = s.data.get(y);
					table.addData(sd.id, sd.value);
				}
				adapter.add(table);
			}
		}
	}

	@NonNull
	@Override
	public Failure<String> onEnter(@NonNull FragmentActivity activity, @NonNull FloatingActionButton fabOne, @NonNull FloatingActionButton fabTwo) {
		adapter = new TableAdapter(activity, 16);
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
		if (view != null) {
			((ListView)view.findViewById(R.id.stats_list_view)).smoothScrollToPosition(0);
		}
	}

}

