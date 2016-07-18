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
import com.adsamcik.signalcollector.Network;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.Setting;
import com.adsamcik.signalcollector.interfaces.ICallback;
import com.adsamcik.signalcollector.interfaces.ITabFragment;
import com.adsamcik.signalcollector.services.TrackerService;

public class FragmentMain extends Fragment implements ITabFragment {
	private final String activity_name = "MainActivity";
	private TextView textTime, textPosition, textAccuracy, textWifiCount, textCurrentCell, textCellCount, textPressure, textActivity, textCollected;
	private BroadcastReceiver receiver;

	private FloatingActionButton fabTrack, fabUp;

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_main, container, false);
		if (!getActivity().getClass().getSimpleName().equals(activity_name))
			throw new RuntimeException("Main fragment is attached to different activity than " + activity_name);

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

		receiver = new UpdateInfoReceiver();
		return view;
	}

	private void setCollected(Resources r, long collected) {
		textCollected.setText(String.format(r.getString(R.string.main_collected), Extensions.humanReadableByteCount(collected)));
	}

	@Override
	public void onDestroy() {
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
		} else if (Build.VERSION.SDK_INT >= 23) {
			MainActivity.instance.requestPermissions(requiredPermissions, 0);
		}
	}


	/**
	 * 0 - No cloud sync required
	 * 1 - Data available for sync
	 * 2 - Syncing data
	 * 3 - Cloud error
	 */
	public void setCloudStatus(int status) {
		if (fabUp == null)
			throw new RuntimeException("upload fab is null. This should not happen.");
		else if (status < 0 || status > 3)
			throw new RuntimeException("Status is out of range");

		switch (status) {
			case 0:
				fabUp.hide();
				fabUp.setOnClickListener(null);
				break;
			case 1:
				fabUp.setImageResource(R.drawable.ic_file_upload_24dp);
				fabUp.setOnClickListener(
						v -> {
							setCloudStatus(2);
							DataStore.requestUpload(getContext(), false);
						}
				);
				fabUp.show();
				break;
			case 2:
				fabUp.setImageResource(R.drawable.ic_cloud_upload_24dp);
				fabUp.setOnClickListener(null);
				fabUp.show();
				break;
			case 3:
				fabUp.setImageResource(R.drawable.ic_cloud_off_24dp);
				fabUp.setOnClickListener(null);
				break;
		}

		Network.cloudStatus = status;
	}

	/**
	 * 0 - start tracking icon
	 * 1 - stop tracking icon
	 * 2 - saving icon
	 */
	private void changeTrackerButton(int status) {
		switch (status) {
			case 0:
				fabTrack.setImageResource(R.drawable.ic_play_arrow_24dp);
				break;
			case 1:
				fabTrack.setImageResource(R.drawable.ic_pause_24dp);
				break;
			case 2:
				fabTrack.setImageResource(R.drawable.ic_loop_24dp);
				break;

		}
	}

	private void RecountData() {
		if (DataStore.recountDataSize() > 0)
			setCloudStatus(1);
		else
			setCloudStatus(0);
	}

	@Override
	public boolean onEnter(Activity activity, FloatingActionButton fabOne, FloatingActionButton fabTwo) {
		fabTrack = fabOne;
		fabUp = fabTwo;

		fabTrack.show();
		RecountData();

		changeTrackerButton(TrackerService.isActive ? 1 : 0);
		fabTrack.setOnClickListener(
				v -> {
					if (TrackerService.isActive)
						TrackerService.setAutoLock();
					toggleCollecting(!TrackerService.isActive);
				}
		);

		IntentFilter filter = new IntentFilter(Setting.BROADCAST_UPDATE_INFO);
		LocalBroadcastManager.getInstance(getContext()).registerReceiver(receiver, filter);

		DataStore.onDataChanged = new ICallback() {
			@Override
			public void OnTrue() {
				setCloudStatus(1);
			}

			@Override
			public void OnFalse() {
				setCloudStatus(0);
			}
		};

		DataStore.onUpload = new ICallback() {
			@Override
			public void OnTrue() {
				setCloudStatus(2);
			}

			@Override
			public void OnFalse() {
				RecountData();
			}
		};

		return true;
	}

	@Override
	public void onLeave() {
		LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(receiver);
		DataStore.onDataChanged = null;
		DataStore.onUpload = null;
	}

	class UpdateInfoReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			Resources res = getResources();
			if (Network.cloudStatus == 0) setCloudStatus(1);

			setCollected(res, intent.getLongExtra("approxSize", 0));

			textTime.setText(String.format(res.getString(R.string.main_last_update), DateFormat.format("HH:mm:ss", intent.getLongExtra("time", 0))));

			int wifiCount = intent.getIntExtra("wifiCount", -1);
			if (wifiCount >= 0)
				textWifiCount.setText(String.format(res.getString(R.string.main_wifi_count), wifiCount));

			int cellCount = intent.getIntExtra("cellCount", -1);
			if (cellCount >= 0) {
				textCurrentCell.setText(String.format(res.getString(R.string.main_cell_current), intent.getStringExtra("cellType"), intent.getIntExtra("cellDbm", -1), intent.getIntExtra("cellAsu", -1)));
				textCellCount.setText(String.format(res.getString(R.string.main_cell_count), cellCount));
			}


			textAccuracy.setText(String.format(res.getString(R.string.main_accuracy), intent.getIntExtra("accuracy", -1)));

			textPosition.setText(String.format(res.getString(R.string.main_position),
					Extensions.coordsToString(intent.getDoubleExtra("latitude", -1)),
					Extensions.coordsToString(intent.getDoubleExtra("longitude", -1)),
					(int) intent.getDoubleExtra("altitude", -1)));

			float pressure = intent.getFloatExtra("pressure", -1);
			if (pressure >= 0)
				textPressure.setText(String.format(res.getString(R.string.main_pressure), pressure));

			textActivity.setText(String.format(res.getString(R.string.main_activity), intent.getStringExtra("activity")));
		}
	}
}
