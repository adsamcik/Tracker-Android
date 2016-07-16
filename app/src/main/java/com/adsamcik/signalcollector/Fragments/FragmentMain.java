package com.adsamcik.signalcollector.fragments;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v4.content.LocalBroadcastManager;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.adsamcik.signalcollector.DataStore;
import com.adsamcik.signalcollector.Extensions;
import com.adsamcik.signalcollector.MainActivity;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.Setting;
import com.adsamcik.signalcollector.services.TrackerService;

public class FragmentMain extends Fragment implements ITabFragment {
	private final String activity_name = "MainActivity";
	private MainActivity activity;
	private TextView textTime, textPosition, textAccuracy, textWifiCount, textCurrentCell, textCellCount, textPressure, textActivity, textCollected;
	private BroadcastReceiver receiver;

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_main, container, false);
		if(!getActivity().getClass().getSimpleName().equals(activity_name))
			throw new RuntimeException("Main fragment is attached to different activity than " + activity_name);

		activity = (MainActivity) getActivity();
		textAccuracy = (TextView) view.findViewById(R.id.textAccuracy);
		textPosition = (TextView) view.findViewById(R.id.textPosition);
		textCellCount = (TextView) view.findViewById(R.id.textCells);
		textCurrentCell = (TextView) view.findViewById(R.id.textCurrentCell);
		textWifiCount = (TextView) view.findViewById(R.id.textWifiCount);
		textTime = (TextView) view.findViewById(R.id.textTime);
		textPressure = (TextView) view.findViewById(R.id.textPressure);
		textActivity = (TextView) view.findViewById(R.id.textActivity);
		textCollected = (TextView) view.findViewById(R.id.textCollected);

		setCollected(getResources(), DataStore.sizeOfData());

		IntentFilter filter = new IntentFilter(UpdateInfoReceiver.BROADCAST_TAG);
		receiver = new UpdateInfoReceiver();
		LocalBroadcastManager.getInstance(getContext()).registerReceiver(receiver, filter);
		return view;
	}

	private void setCollected(Resources r, long collected) {
		textCollected.setText(String.format(r.getString(R.string.main_collected), Extensions.humanReadableByteCount(collected)));
	}

	@Override
	public void onDestroy() {
		LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(receiver);
		super.onDestroy();
	}

	/**
	 * Enables or disables collecting service
	 *
	 * @param enable ensures intended action
	 */
	private void toggleCollecting(boolean enable) {
		if (TrackerService.isActive == enable)
			return;

		String[] requiredPermissions = Extensions.checkTrackingPermissions(MainActivity.context);

		if (requiredPermissions == null) {
			if (!TrackerService.isActive) {
				Setting.getPreferences(MainActivity.context).edit().putBoolean(Setting.STOP_TILL_RECHARGE, false).apply();
				Intent trackerService = new Intent(MainActivity.context, TrackerService.class);
				trackerService.putExtra("approxSize", DataStore.sizeOfData());
				MainActivity.instance.startService(trackerService);
				TrackerService.service = trackerService;
			} else {
				MainActivity.instance.stopService(TrackerService.service);
			}
		}
		else if(Build.VERSION.SDK_INT >= 23){
			MainActivity.instance.requestPermissions(requiredPermissions, 0);
		}
	}

	@Override
	public boolean onEnter(Activity activity, FloatingActionButton fabOne, FloatingActionButton fabTwo) {
		fabOne.show();
		changeTrackerButton(TrackerService.isActive ? 1 : 0);
		fabOne.setOnClickListener(
				v -> {
					if (TrackerService.isActive)
						TrackerService.setAutoLock();
					toggleCollecting(!TrackerService.isActive);
				}
		);

		setCloudStatus(cloudStatus);
		fabTwo.setOnClickListener(
				v -> {
					if (cloudStatus == 1) {
						setCloudStatus(2);
						DataStore.requestUpload(context, false);
					}
				}
		);
		return true;
	}

	@Override
	public void onLeave() {

	}

	public class UpdateInfoReceiver extends BroadcastReceiver {
		public static final String BROADCAST_TAG = "SignalsUpdate";

		@Override
		public void onReceive(Context context, Intent intent) {
			Resources res = getResources();
			if(activity.getCloudStatus() == 0) activity.setCloudStatus(1);

			setCollected(res, intent.getLongExtra("approxSize", 0));

			textTime.setText(String.format(res.getString(R.string.main_last_update), DateFormat.format("HH:mm:ss", intent.getLongExtra("time", 0))));

			int wifiCount = intent.getIntExtra("wifiCount", -1);
			if(wifiCount >= 0)
				textWifiCount.setText(String.format(res.getString(R.string.main_wifi_count), wifiCount));

			int cellCount = intent.getIntExtra("cellCount", -1);
			if(cellCount >= 0) {
				textCurrentCell.setText(String.format(res.getString(R.string.main_cell_current), intent.getStringExtra("cellType"), intent.getIntExtra("cellDbm", -1), intent.getIntExtra("cellAsu", -1)));
				textCellCount.setText(String.format(res.getString(R.string.main_cell_count), cellCount));
			}


			textAccuracy.setText(String.format(res.getString(R.string.main_accuracy), intent.getIntExtra("accuracy", -1)));

			textPosition.setText(String.format(res.getString(R.string.main_position),
					Extensions.coordsToString(intent.getDoubleExtra("latitude", -1)),
					Extensions.coordsToString(intent.getDoubleExtra("longitude", -1)),
					(int) intent.getDoubleExtra("altitude", -1)));

			float pressure = intent.getFloatExtra("pressure", -1);
			if(pressure >= 0)
				textPressure.setText(String.format(res.getString(R.string.main_pressure), pressure));

			textActivity.setText(String.format(res.getString(R.string.main_activity), intent.getStringExtra("activity")));
		}
	}
}
