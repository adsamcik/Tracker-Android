package com.adsamcik.signalcollector.fragments;

import android.animation.Animator;
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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.adsamcik.signalcollector.Assist;
import com.adsamcik.signalcollector.classes.DataStore;
import com.adsamcik.signalcollector.classes.Network;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.Setting;
import com.adsamcik.signalcollector.classes.Success;
import com.adsamcik.signalcollector.data.CellData;
import com.adsamcik.signalcollector.data.Data;
import com.adsamcik.signalcollector.interfaces.ITabFragment;
import com.adsamcik.signalcollector.services.TrackerService;
import com.adsamcik.signalcollector.services.UploadService;
import com.google.firebase.crash.FirebaseCrash;

public class FragmentMain extends Fragment implements ITabFragment {
	private LinearLayout layoutCell, layoutWifi, layoutOther;
	private TextView textTime, textPosition, textAccuracy, textWifiCount, textWifiTime, textCurrentCell, textCellCount, textPressure, textActivity, textCollected, textNoise;
	private ProgressBar progressBar;

	private AnimatedVectorDrawable playToPause, pauseToPlay;
	private FloatingActionButton fabTrack, fabUp;

	private long lastWifiTime = 0;

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_main, container, false);
		final String activity_name = "MainActivity";
		if (!getActivity().getClass().getSimpleName().equals(activity_name))
			throw new RuntimeException("Main fragment is attached to different activity than " + activity_name);

		textAccuracy = (TextView) view.findViewById(R.id.textAccuracy);
		textPosition = (TextView) view.findViewById(R.id.textPosition);
		textCellCount = (TextView) view.findViewById(R.id.textCells);
		textCurrentCell = (TextView) view.findViewById(R.id.textCurrentCell);
		textWifiCount = (TextView) view.findViewById(R.id.textWifiCount);
		textWifiTime = (TextView) view.findViewById(R.id.textWifiTime);
		textTime = (TextView) view.findViewById(R.id.textTime);
		textPressure = (TextView) view.findViewById(R.id.textPressure);
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

		if (fabTrack != null)
			updateData(getContext());

		return view;
	}

	private void setCollected(long collected) {
		if (Network.cloudStatus == 0 && collected > 0)
			setCloudStatus(1);
		else if (collected == 0)
			setCloudStatus(0);
		if (textCollected != null && getResources() != null)
			textCollected.setText(String.format(getResources().getString(R.string.main_collected), Assist.humanReadableByteCount(collected)));
	}

	/**
	 * 0 - start tracking icon
	 * 1 - stop tracking icon
	 * 2 - saving icon
	 */
	private void changeTrackerButton(int status) {
		switch (status) {
			case 0:
				fabTrack.setImageDrawable(playToPause);
				playToPause.start();
				break;
			case 1:
				fabTrack.setImageDrawable(pauseToPlay);
				pauseToPlay.start();
				break;
		}
	}

	/**
	 * Enables or disables collecting service
	 *
	 * @param enable ensures intended action
	 */
	private void toggleCollecting(Activity activity, boolean enable) {
		if (TrackerService.service != null == enable)
			return;

		String[] requiredPermissions = Assist.checkTrackingPermissions(activity);

		if (requiredPermissions == null) {
			if (TrackerService.service == null) {
				Setting.getPreferences(activity).edit().putBoolean(Setting.STOP_TILL_RECHARGE, false).apply();
				Intent trackerService = new Intent(activity, TrackerService.class);
				trackerService.putExtra("approxSize", DataStore.sizeOfData());
				activity.startService(trackerService);
			} else {
				if (TrackerService.service == null)
					FirebaseCrash.report(new Exception("Tracker service is null"));
				activity.stopService(TrackerService.service);
			}

		} else if (Build.VERSION.SDK_INT >= 23) {
			activity.requestPermissions(requiredPermissions, 0);
		}
	}


	/**
	 * 0 - No cloud sync required
	 * 1 - Data available for sync
	 * 2 - Syncing data
	 * 3 - Cloud error
	 */
	private void setCloudStatus(int status) {
		if (fabUp == null)
			return;
		else if (status < 0 || status > 3)
			throw new RuntimeException("Status is out of range");

		switch (status) {
			case 0:
				fabUp.hide();
				fabUp.setOnClickListener(null);
				break;
			case 1:
				fabUp.setImageResource(R.drawable.ic_cloud_upload_24dp);
				fabUp.setOnClickListener(
						v -> {
							setCloudStatus(2);
							DataStore.requestUpload(getContext(), false);
						}
				);
				fabUp.show();
				break;
			case 2:
				fabUp.setImageResource(R.drawable.ic_sync_black_24dp);
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

	void updateUploadProgress(final Context context, final int percentage) {
		progressBar.setVisibility(View.VISIBLE);
		ObjectAnimator animation = ObjectAnimator.ofInt(progressBar, "progress", percentage);
		animation.setDuration(400);
		animation.setInterpolator(new LinearInterpolator());
		animation.start();
		animation.addListener(new Animator.AnimatorListener() {
			@Override
			public void onAnimationStart(Animator animator) {

			}

			@Override
			public void onAnimationEnd(Animator animator) {
				if (percentage == 100) {
					fabUp.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.colorAccent)));
					fabUp.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.textPrimary)));
					fabUp.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_check_black_24dp));

					AlphaAnimation fadeOutAnimation = new AlphaAnimation(1.0f, 0.0f);//fade from 1 to 0 alpha
					fadeOutAnimation.setDuration(300);
					fadeOutAnimation.setFillAfter(true);
					progressBar.startAnimation(fadeOutAnimation);

					//todo fab can be hidden by this when other tab is active
					new Handler().postDelayed(fabUp::hide, 600);
					new Handler().postDelayed(() -> {
						fabUp.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.textPrimary)));
						fabUp.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.colorAccent)));
						progressBar.setProgress(0);
					}, 1000);
				}
			}

			@Override
			public void onAnimationCancel(Animator animator) {

			}

			@Override
			public void onAnimationRepeat(Animator animator) {

			}
		});
	}

	@Override
	public Success<String> onEnter(final FragmentActivity activity, final FloatingActionButton fabOne, final FloatingActionButton fabTwo) {
		fabTrack = fabOne;
		fabUp = fabTwo;
		progressBar = (ProgressBar) ((ViewGroup) fabTwo.getParent()).findViewById(R.id.progressBar);
		int percentage = UploadService.getUploadPercentage();
		if (percentage > 0)
			updateUploadProgress(activity, percentage);
		fabTrack.show();

		if (playToPause == null) {
			playToPause = (AnimatedVectorDrawable) ContextCompat.getDrawable(activity, R.drawable.avd_play_to_pause);
			pauseToPlay = (AnimatedVectorDrawable) ContextCompat.getDrawable(activity, R.drawable.avd_pause_to_play);
		}

		changeTrackerButton(TrackerService.service != null ? 1 : 0);
		fabTrack.setOnClickListener(
				v -> {
					if (TrackerService.service != null)
						TrackerService.setAutoLock();
					toggleCollecting(activity, TrackerService.service == null);
				}
		);
		DataStore.setOnDataChanged(() -> activity.runOnUiThread(() -> setCollected(DataStore.sizeOfData())));
		DataStore.setOnUploadProgress((progress) -> activity.runOnUiThread(() -> updateCollected(progress)));
		TrackerService.onNewDataFound = () -> activity.runOnUiThread(this::updateData);
		TrackerService.onServiceStateChange = () -> activity.runOnUiThread(() -> changeTrackerButton(TrackerService.service != null ? 1 : 0));

		setCloudStatus(DataStore.sizeOfData() == 0 ? 0 : 1);

		//TrackerService.dataEcho = new Data(200).setPressure(50).setActivity(1).setCell("test", new CellData[0]).setLocation(new Location("test")).setWifi(new android.net.wifi.ScanResult[0], 10);

		if (layoutWifi != null)
			updateData(activity);
		return new Success<>();
	}

	@Override
	public void onLeave() {
		DataStore.setOnDataChanged(null);
		DataStore.setOnUploadProgress(null);
		TrackerService.onNewDataFound = null;
		TrackerService.onServiceStateChange = null;
		progressBar.setVisibility(View.INVISIBLE);
		fabUp.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(getContext(), R.color.textPrimary)));
		fabUp.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(getContext(), R.color.colorAccent)));
	}

	@Override
	public void onPermissionResponse(int requestCode, boolean success) {

	}

	@Override
	public ITabFragment newInstance() {
		return new FragmentMain();
	}

	private void updateCollected(int progress) {
		long size;
		if (progress == 100) {
			DataStore.cleanup();
			size = DataStore.recountDataSize();
		} else
			size = DataStore.sizeOfData();
		updateUploadProgress(getContext(), progress);
		setCollected(size);
	}

	private void updateData(@NonNull Context context) {
		Resources res = context.getResources();
		Data d = TrackerService.dataEcho;
		setCollected(DataStore.sizeOfData());

		if (d != null) {
			textTime.setText(String.format(res.getString(R.string.main_last_update), DateFormat.format("HH:mm:ss", d.time)));

			if (d.wifi != null) {
				textWifiCount.setText(String.format(res.getString(R.string.main_wifi_count), d.wifi.length));
				layoutWifi.setVisibility(View.VISIBLE);
				textWifiTime.setText(String.format(res.getString(R.string.main_wifi_time), d.time - d.wifiTime, TrackerService.distanceToWifi));
				lastWifiTime = d.time;
			} else if (lastWifiTime - d.time > 10000)
				layoutWifi.setVisibility(View.GONE);

			if (d.cellCount != -1) {
				CellData active = d.getActiveCell();
				if (active != null) {
					textCurrentCell.setVisibility(View.VISIBLE);
					textCurrentCell.setText(String.format(res.getString(R.string.main_cell_current), active.getType(), active.dbm, active.asu));
				} else
					textCurrentCell.setVisibility(View.GONE);
				textCellCount.setText(String.format(res.getString(R.string.main_cell_count), d.cellCount));
				layoutCell.setVisibility(View.VISIBLE);
			} else
				layoutCell.setVisibility(View.GONE);


			textAccuracy.setText(String.format(res.getString(R.string.main_accuracy), (int) d.accuracy));

			textPosition.setText(String.format(res.getString(R.string.main_position),
					Assist.coordsToString(d.latitude),
					Assist.coordsToString(d.longitude),
					(int) d.altitude));

			if (d.pressure > 0) {
				textPressure.setText(String.format(res.getString(R.string.main_pressure), d.pressure));
				layoutOther.setVisibility(View.VISIBLE);
			} else
				layoutOther.setVisibility(View.GONE);

			if (d.noise > 0) {
				textNoise.setText(String.format(res.getString(R.string.main_noise), (int) d.noise, (int) Assist.amplitudeToDbm(d.noise)));
				textNoise.setVisibility(View.VISIBLE);
			} else if (Setting.getPreferences(context).getBoolean(Setting.TRACKING_NOISE_ENABLED, false)) {
				textNoise.setText(res.getString(R.string.main_noise_not_tracked));
				textNoise.setVisibility(View.VISIBLE);
			} else
				textNoise.setVisibility(View.GONE);

			textActivity.setText(String.format(res.getString(R.string.main_activity), Assist.getActivityName(d.activity)));
		}
	}

	private void updateData() {
		updateData(getContext());
	}

}
