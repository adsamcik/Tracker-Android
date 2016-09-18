package com.adsamcik.signalcollector.fragments;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.res.ResourcesCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import com.adsamcik.signalcollector.classes.DataStore;
import com.adsamcik.signalcollector.classes.Success;
import com.adsamcik.signalcollector.interfaces.ITabFragment;
import com.adsamcik.signalcollector.play.PlayController;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.Setting;

public class FragmentSettings extends Fragment implements ITabFragment {
	private final String TAG = "SignalsSettings";
	private final int REQUEST_CODE_PERMISSIONS_MICROPHONE = 401;

	private String[] mTrackingString, mAutoupString;
	private ImageView mTrackingNone, mTrackingOnFoot, mTrackingAlways;
	private ImageView mAutoupDisabled, mAutoupWifi, mAutoupAlways;
	private TextView textView_PlayLog, mAutoupDesc, mTrackDesc;
	private Switch switchNoise;

	private SharedPreferences mSharedPreferences;

	private ImageView mTrackingSelected, mAutoupSelected;

	private ColorStateList mSelectedState;
	private ColorStateList mDefaultState;

	private void updateTracking(int select) {
		mSharedPreferences.edit().putInt(Setting.BACKGROUND_TRACKING, select).apply();
		ImageView selected;
		switch (select) {
			case 0:
				selected = mTrackingNone;
				break;
			case 1:
				selected = mTrackingOnFoot;
				break;
			case 2:
				selected = mTrackingAlways;
				break;
			default:
				return;
		}
		mTrackDesc.setText(mTrackingString[select]);
		if (mTrackingSelected != null)
			mTrackingSelected.setImageTintList(mDefaultState);
		selected.setImageTintList(mSelectedState);
		mTrackingSelected = selected;
	}

	private void updateAutoup(int select) {
		mSharedPreferences.edit().putInt(Setting.AUTO_UPLOAD, select).apply();
		ImageView selected;
		switch (select) {
			case 0:
				selected = mAutoupDisabled;
				break;
			case 1:
				selected = mAutoupWifi;
				break;
			case 2:
				selected = mAutoupAlways;
				break;
			default:
				return;
		}
		mAutoupDesc.setText(mAutoupString[select]);
		if (mAutoupSelected != null)
			mAutoupSelected.setImageTintList(mDefaultState);
		selected.setImageTintList(mSelectedState);
		mAutoupSelected = selected;
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		final View rootView = inflater.inflate(R.layout.fragment_settings, container, false);
		final Context c = getContext();
		mSharedPreferences = Setting.getPreferences(c);
		final Resources resources = getResources();

		try {
			((TextView) rootView.findViewById(R.id.versionNum)).setText(c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName);
		} catch (Exception e) {
			Log.d(TAG, "Failed to set version");
		}

		mSelectedState = ResourcesCompat.getColorStateList(resources, R.color.selected_value, getContext().getTheme());
		mDefaultState = ResourcesCompat.getColorStateList(resources, R.color.default_value, getContext().getTheme());

		mTrackingString = resources.getStringArray(R.array.background_tracking_options);
		mAutoupString = resources.getStringArray(R.array.automatic_upload_options);

		mAutoupDesc = (TextView) rootView.findViewById(R.id.autoupload_description);
		mTrackDesc = (TextView) rootView.findViewById(R.id.tracking_description);

		mTrackingNone = (ImageView) rootView.findViewById(R.id.tracking_none);
		mTrackingNone.setOnClickListener(v -> updateTracking(0));
		mTrackingOnFoot = (ImageView) rootView.findViewById(R.id.tracking_onfoot);
		mTrackingOnFoot.setOnClickListener(v -> updateTracking(1));
		mTrackingAlways = (ImageView) rootView.findViewById(R.id.tracking_always);
		mTrackingAlways.setOnClickListener(v -> updateTracking(2));

		mAutoupDisabled = (ImageView) rootView.findViewById(R.id.autoupload_disabled);
		mAutoupDisabled.setOnClickListener(v -> updateAutoup(0));
		mAutoupWifi = (ImageView) rootView.findViewById(R.id.autoupload_wifi);
		mAutoupWifi.setOnClickListener(v -> updateAutoup(1));
		mAutoupAlways = (ImageView) rootView.findViewById(R.id.autoupload_always);
		mAutoupAlways.setOnClickListener(v -> updateAutoup(2));

		updateTracking(mSharedPreferences.getInt(Setting.BACKGROUND_TRACKING, 1));
		updateAutoup(mSharedPreferences.getInt(Setting.AUTO_UPLOAD, 1));

		textView_PlayLog = (TextView) rootView.findViewById(R.id.play_loginButton);

		if (PlayController.gamesController != null)
			PlayController.gamesController.setUI(rootView);

		textView_PlayLog.setOnClickListener(v -> {
			if (!PlayController.isLogged())
				PlayController.initializeGamesClient(rootView, getActivity());
			else {
				textView_PlayLog.setText(R.string.settings_playGamesLogin);
				PlayController.destroyGamesClient();
			}

		});

		rootView.findViewById(R.id.play_achievements).setOnClickListener(v -> {
			if (PlayController.isLogged())
				PlayController.gamesController.showAchievements(getActivity());
			else
				PlayController.initializeGamesClient(rootView, getActivity());
		});

		rootView.findViewById(R.id.other_clear).setOnClickListener(v -> {
			AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(getContext(), R.style.AlertDialog);
			alertDialogBuilder
					.setPositiveButton(getResources().getText(R.string.alert_clear_confirm), (dialog, which) -> DataStore.clearAllData())
					.setNegativeButton(getResources().getText(R.string.alert_clear_cancel), (dialog, which) -> {})
					.setMessage(getResources().getText(R.string.alert_clear_text));

			alertDialogBuilder.create().show();
		});

		setSwitchChangeListener(c, Setting.TRACKING_WIFI_ENABLED, (Switch) rootView.findViewById(R.id.switchTrackWifi), true);
		setSwitchChangeListener(c, Setting.TRACKING_CELL_ENABLED, (Switch) rootView.findViewById(R.id.switchTrackCell), true);
		setSwitchChangeListener(c, Setting.TRACKING_PRESSURE_ENABLED, (Switch) rootView.findViewById(R.id.switchTrackPressure), true);

		switchNoise = (Switch) rootView.findViewById(R.id.switchTrackNoise);
		switchNoise.setChecked(Setting.getPreferences(c).getBoolean(Setting.TRACKING_NOISE_ENABLED, false));
		switchNoise.setOnCheckedChangeListener((CompoundButton compoundButton, boolean b) -> {
			if (b && Build.VERSION.SDK_INT > 22 && ContextCompat.checkSelfPermission(c, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
				getActivity().requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_PERMISSIONS_MICROPHONE);
			else
				Setting.getPreferences(c).edit().putBoolean(Setting.TRACKING_NOISE_ENABLED, b).apply();
		});

		return rootView;
	}

	private void setSwitchChangeListener(final Context context, final String name, Switch s, final boolean defaultState) {
		s.setChecked(Setting.getPreferences(context).getBoolean(name, defaultState));
		s.setOnCheckedChangeListener((CompoundButton compoundButton, boolean b) -> Setting.getPreferences(context).edit().putBoolean(name, b).apply());
	}

	@Override
	public Success<String> onEnter(FragmentActivity activity, FloatingActionButton fabOne, FloatingActionButton fabTwo) {
		fabOne.hide();
		fabTwo.hide();
		return new Success<>();
	}

	@Override
	public void onLeave() {

	}

	@Override
	public void onPermissionResponse(int requestCode, boolean success) {
		switch (requestCode) {
			case REQUEST_CODE_PERMISSIONS_MICROPHONE:
				if (success)
					Setting.getPreferences(getContext()).edit().putBoolean(Setting.TRACKING_NOISE_ENABLED, true).apply();
				else
					switchNoise.setChecked(false);
				break;
			default:
				throw new UnsupportedOperationException("Permissions with request code " + requestCode + " has no defined behavior");
		}
	}

	@Override
	public ITabFragment newInstance() {
		return new FragmentSettings();
	}
}
