package com.adsamcik.signalcollector.fragments;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.ContextCompat;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.adsamcik.signalcollector.enums.CloudStatus;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Failure;
import com.adsamcik.signalcollector.utility.FirebaseAssist;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.utility.DataStore;
import com.adsamcik.signalcollector.utility.Network;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.data.CellData;
import com.adsamcik.signalcollector.data.Data;
import com.adsamcik.signalcollector.interfaces.ITabFragment;
import com.adsamcik.signalcollector.services.TrackerService;
import com.adsamcik.signalcollector.services.UploadService;
import com.adsamcik.signalcollector.utility.SnackMaker;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crash.FirebaseCrash;

public class FragmentTracker extends Fragment implements ITabFragment {
	private LinearLayout layoutCell, layoutWifi, layoutOther;
	private TextView textTime, textPosition, textAccuracy, textWifiCount, textWifiCollection, textCurrentCell, textCellCount, textActivity, textCollected, textNoise;
	private ProgressBar progressBar;

	private AnimatedVectorDrawable pauseToPlay, playToPause;
	private FloatingActionButton fabTrack, fabUp;

	private long lastWifiTime = 0;

	private Handler handler;

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_main, container, false);
		final String activity_name = "MainActivity";
		if (!getActivity().getClass().getSimpleName().equals(activity_name))
			throw new RuntimeException("Main fragment is attached to different activity than " + activity_name);

		textAccuracy = (TextView) view.findViewById(R.id.textAccuracy);
		textPosition = (TextView) view.findViewById(R.id.textPosition);
		textCellCount = (TextView) view.findViewById(R.id.textCellCount);
		textCurrentCell = (TextView) view.findViewById(R.id.textCurrentCell);
		textWifiCount = (TextView) view.findViewById(R.id.textWifiCount);
		textWifiCollection = (TextView) view.findViewById(R.id.textWifiCollection);
		textTime = (TextView) view.findViewById(R.id.textTime);
		textNoise = (TextView) view.findViewById(R.id.textNoise);
		textActivity = (TextView) view.findViewById(R.id.textActivity);
		textCollected = (TextView) view.findViewById(R.id.textCollected);

		layoutWifi = (LinearLayout) view.findViewById(R.id.layout_wifi);
		layoutCell = (LinearLayout) view.findViewById(R.id.layout_cells);
		layoutOther = (LinearLayout) view.findViewById(R.id.layout_other);

		layoutWifi.setVisibility(View.GONE);
		layoutCell.setVisibility(View.GONE);
		layoutOther.setVisibility(View.GONE);

		long dataSize = DataStore.sizeOfData();
		setCollected(dataSize);

		updateData(getContext());

		return view;
	}


	private void setCollected(long collected) {
		if (textCollected != null && getResources() != null)
			textCollected.setText(String.format(getResources().getString(R.string.main_collected), Assist.humanReadableByteCount(collected, true)));
	}

	/**
	 * 0 - start tracking icon
	 * 1 - stop tracking icon
	 * 2 - saving icon
	 */
	private void changeTrackerButton(int status, boolean animate) {
		switch (status) {
			case 0:
				if (animate) {
					fabTrack.setImageDrawable(playToPause);
					playToPause.start();
				} else
					fabTrack.setImageDrawable(pauseToPlay);
				break;
			case 1:
				if (animate) {
					fabTrack.setImageDrawable(pauseToPlay);
					pauseToPlay.start();
				} else
					fabTrack.setImageDrawable(playToPause);
				break;
		}
	}

	/**
	 * Enables or disables collecting service
	 *
	 * @param enable ensures intended action
	 */
	private void toggleCollecting(Activity activity, boolean enable) {
		if (TrackerService.isRunning() == enable)
			return;

		String[] requiredPermissions = Assist.checkTrackingPermissions(activity);

		if (requiredPermissions == null) {
			if (!TrackerService.isRunning()) {
				if(!Assist.isGNSSEnabled(activity)) {
					new SnackMaker(activity).showSnackbar(R.string.error_gnss_not_enabled, R.string.enable, (v) -> {
						Intent gpsOptionsIntent = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
						startActivity(gpsOptionsIntent);
					});
				} else {
					Preferences.get(activity).edit().putBoolean(Preferences.PREF_STOP_TILL_RECHARGE, false).apply();
					Intent trackerService = new Intent(activity, TrackerService.class);
					trackerService.putExtra("backTrack", false);
					activity.startService(trackerService);
				}
			} else {
				activity.stopService(new Intent(activity, TrackerService.class));
			}

		} else if (Build.VERSION.SDK_INT >= 23) {
			activity.requestPermissions(requiredPermissions, 0);
		}
	}


	private void updateUploadButton() {
		if (fabUp == null || Network.cloudStatus == null) {
			Log.e("SignalsTrackerFragment", "fab " + (fabUp == null ? " is null " : " is fine ") + " status " + (Network.cloudStatus == null ? " is null " : " is fine"));
			FirebaseCrash.report(new Exception("fab " + (fabUp == null ? " is null " : " is fine ") + " status " + (Network.cloudStatus == null ? " is null " : " is fine")));
			return;
		}

		switch (Network.cloudStatus) {
			case NO_SYNC_REQUIRED:
				fabUp.hide();
				fabUp.setOnClickListener(null);
				break;
			case SYNC_REQUIRED:
				fabUp.setImageResource(R.drawable.ic_cloud_upload_24dp);
				progressBar.setVisibility(View.GONE);
				fabUp.setOnClickListener(
						v -> {
							final Context context = getContext();
							Failure<String> failure = UploadService.requestUpload(context, UploadService.UploadScheduleSource.USER);
							FirebaseAnalytics.getInstance(context).logEvent(FirebaseAssist.MANUAL_UPLOAD_EVENT, new Bundle());
							if (failure.hasFailed())
								new SnackMaker(getActivity()).showSnackbar(failure.value);
							else {
								updateUploadProgress(0);
								updateUploadButton();
							}
						}
				);
				fabUp.show();
				break;
			case SYNC_SCHEDULED:
				fabUp.setImageResource(R.drawable.ic_cloud_queue_black_24dp);
				fabUp.setOnClickListener(
						v -> {
							final Context context = getContext();
							Failure<String> failure = UploadService.requestUpload(context, UploadService.UploadScheduleSource.USER);
							FirebaseAnalytics.getInstance(context).logEvent(FirebaseAssist.MANUAL_UPLOAD_EVENT, new Bundle());
							if (failure.hasFailed())
								new SnackMaker(getActivity()).showSnackbar(failure.value);
							else {
								updateUploadButton();
							}
						}
				);
				fabUp.show();
				break;
			case SYNC_IN_PROGRESS:
				fabUp.setImageResource(R.drawable.ic_sync_black_24dp);
				fabUp.setOnClickListener(null);
				fabUp.show();
				break;
			case ERROR:
				fabUp.setImageResource(R.drawable.ic_cloud_off_24dp);
				fabUp.setOnClickListener(null);
				fabUp.show();
				break;
		}
	}

	void updateUploadProgress(final int percentage) {
		final Context context = getActivity();
		progressBar.setVisibility(View.VISIBLE);
		fabUp.setElevation(0);
		if (handler == null)
			handler = new Handler();

		if (percentage == 0) {
			progressBar.setIndeterminate(true);
			updateUploadButton();
		} else if (percentage == -1) {
			progressBar.animate().alpha(0).setDuration(400).start();
			fabUp.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.error)));
			fabUp.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.textPrimary)));
			fabUp.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_close_black_24dp));
			handler.postDelayed(() -> {
				fabUp.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.textPrimary)));
				fabUp.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.colorAccent)));
				updateUploadButton();
				resetFabElevation(fabUp, getResources());
			}, 3000);
		} else {
			progressBar.setIndeterminate(false);
			ObjectAnimator animation = ObjectAnimator.ofInt(progressBar, "progress", percentage);
			animation.setDuration(400);
			if (percentage == 100) {
				handler.postDelayed(() -> {
					fabUp.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.colorAccent)));
					fabUp.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.textPrimary)));
					fabUp.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_check_black_24dp));

					progressBar.animate().alpha(0).setDuration(400).start();

					handler.postDelayed(() -> {
						progressBar.setVisibility(View.GONE);
						if (DataStore.sizeOfData() > 0) {
							fabUp.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.textPrimary)));
							fabUp.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.colorAccent)));
							resetFabElevation(fabUp, getResources());
							updateUploadButton();
						} else {
							fabUp.hide();
							handler.postDelayed(() -> {
								fabUp.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.textPrimary)));
								fabUp.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.colorAccent)));
								resetFabElevation(fabUp, getResources());
							}, 300);
						}
					}, 1000);
				}, 600);
			}
			animation.start();
		}
	}

	void resetFabElevation(FloatingActionButton fab, Resources resources) {
		fab.setElevation(6 * resources.getDisplayMetrics().density);
	}

	@Override
	public Failure<String> onEnter(@NonNull final FragmentActivity activity, @NonNull final FloatingActionButton fabOne, @NonNull final FloatingActionButton fabTwo) {
		fabTrack = fabOne;
		fabUp = fabTwo;
		progressBar = (ProgressBar) ((ViewGroup) fabTwo.getParent()).findViewById(R.id.progressBar);

		if (UploadService.isUploading() || UploadService.getUploadScheduled(activity) == UploadService.UploadScheduleSource.USER) {
			updateUploadProgress(0);
		} else {
			progressBar.setProgress(0);
		}

		updateUploadButton();

		fabTrack.show();

		if (pauseToPlay == null) {
			pauseToPlay = (AnimatedVectorDrawable) ContextCompat.getDrawable(activity, R.drawable.avd_play_to_pause);
			playToPause = (AnimatedVectorDrawable) ContextCompat.getDrawable(activity, R.drawable.avd_pause_to_play);
		}

		changeTrackerButton(TrackerService.isRunning() ? 1 : 0, false);
		fabTrack.setOnClickListener(
				v -> {
					if (TrackerService.isRunning() && TrackerService.isBackgroundActivated()) {
						int lockedForMinutes = TrackerService.setAutoLock();
						new SnackMaker(activity).showSnackbar(activity.getResources().getQuantityString(R.plurals.notification_auto_tracking_lock, lockedForMinutes, lockedForMinutes));
					} else
						toggleCollecting(activity, !TrackerService.isRunning());
				}
		);
		DataStore.setOnDataChanged(() -> activity.runOnUiThread(() -> setCollected(DataStore.sizeOfData())));
		DataStore.setOnUploadProgress((progress) -> activity.runOnUiThread(() -> updateUploadProgress(progress)));
		TrackerService.onNewDataFound = () -> activity.runOnUiThread(this::updateData);
		TrackerService.onServiceStateChange = () -> activity.runOnUiThread(() -> changeTrackerButton(TrackerService.isRunning() ? 1 : 0, true));

		//TrackerService.dataEcho = new Data(200).setActivity(1).setCell("Some Operator", null).setLocation(new Location("test")).setWifi(new android.net.wifi.ScanResult[0], 10);

		if (layoutWifi != null)
			updateData(activity);

		//if (Assist.isEmulator())
		//	fabUp.hide();
		return new Failure<>();
	}

	@Override
	public void onLeave(@NonNull FragmentActivity activity) {
		if (handler != null)
			handler.removeCallbacksAndMessages(null);

		DataStore.setOnDataChanged(null);
		DataStore.setOnUploadProgress(null);
		TrackerService.onNewDataFound = null;
		TrackerService.onServiceStateChange = null;
		progressBar.setVisibility(View.GONE);
		progressBar.setAlpha(1);
		resetFabElevation(fabUp, activity.getResources());
		fabUp.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.textPrimary)));
		fabUp.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.colorAccent)));
		fabUp = null;
		fabTrack = null;
	}

	@Override
	public void onPermissionResponse(int requestCode, boolean success) {

	}

	@Override
	public void onHomeAction() {
	}

	private void updateData(@NonNull Context context) {
		Resources res = context.getResources();
		Data d = TrackerService.dataEcho;
		setCollected(DataStore.sizeOfData());

		if (DataStore.sizeOfData() > 0 && Network.cloudStatus == CloudStatus.NO_SYNC_REQUIRED) {
			Network.cloudStatus = CloudStatus.SYNC_REQUIRED;
			updateUploadButton();
		}

		if (d != null) {
			textTime.setText(String.format(res.getString(R.string.main_last_update), DateFormat.format("HH:mm:ss", d.time)));

			textAccuracy.setText(String.format(res.getString(R.string.main_accuracy), (int) d.accuracy));

			textPosition.setText(String.format(res.getString(R.string.main_position),
					Assist.coordsToString(d.latitude),
					Assist.coordsToString(d.longitude),
					(int) d.altitude));

			if (d.wifi != null) {
				textWifiCount.setText(String.format(res.getString(R.string.main_wifi_count), d.wifi.length));
				textWifiCollection.setText(String.format(res.getString(R.string.main_wifi_updated), TrackerService.distanceToWifi));
				lastWifiTime = d.time;
				layoutWifi.setVisibility(View.VISIBLE);
			} else if (lastWifiTime - d.time < 10000) {
				textWifiCollection.setText(String.format(res.getString(R.string.main_wifi_updated), TrackerService.distanceToWifi));
			} else {
				layoutWifi.setVisibility(View.GONE);
			}

			if (d.cellCount == -1) {
				layoutCell.setVisibility(View.GONE);
			} else {
				CellData active = d.getActiveCell();
				if (active != null) {
					textCurrentCell.setVisibility(View.VISIBLE);
					textCurrentCell.setText(String.format(res.getString(R.string.main_cell_current), active.getType(), active.dbm, active.asu));
				} else
					textCurrentCell.setVisibility(View.GONE);
				textCellCount.setText(String.format(res.getString(R.string.main_cell_count), d.cellCount));
				layoutCell.setVisibility(View.VISIBLE);
			}


			layoutOther.setVisibility(View.VISIBLE);
			if (d.noise > 0) {
				textNoise.setText(String.format(res.getString(R.string.main_noise), (int) d.noise, (int) Assist.amplitudeToDbm(d.noise)));
			} else if (Preferences.get(context).getBoolean(Preferences.PREF_TRACKING_NOISE_ENABLED, false)) {
				textNoise.setText(res.getString(R.string.main_noise_not_collected));
			} else
				textNoise.setText(res.getString(R.string.main_noise_disabled));

			if (d.activity == -1)
				textActivity.setVisibility(View.GONE);
			else {
				textActivity.setText(String.format(res.getString(R.string.main_activity), Assist.getActivityName(d.activity)));
				textActivity.setVisibility(View.VISIBLE);
			}
		}
	}

	private void updateData() {
		Context c = getContext();
		if (c != null)
			updateData(c);
	}

}
