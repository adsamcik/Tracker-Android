package com.adsamcik.signalcollector.fragments;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.res.ResourcesCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.adsamcik.signalcollector.activities.DebugFileActivity;
import com.adsamcik.signalcollector.activities.MainActivity;
import com.adsamcik.signalcollector.interfaces.IValueCallback;
import com.adsamcik.signalcollector.services.TrackerService;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Failure;
import com.adsamcik.signalcollector.utility.FirebaseAssist;
import com.adsamcik.signalcollector.utility.MapLayer;
import com.adsamcik.signalcollector.utility.Network;
import com.adsamcik.signalcollector.utility.NetworkLoader;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.utility.DataStore;
import com.adsamcik.signalcollector.interfaces.ITabFragment;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.utility.Signin;
import com.adsamcik.signalcollector.utility.SnackMaker;
import com.google.android.gms.common.SignInButton;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crash.FirebaseCrash;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

public class FragmentSettings extends Fragment implements ITabFragment {
	private final String TAG = "SignalsSettings";
	private final int REQUEST_CODE_PERMISSIONS_MICROPHONE = 401;

	private String[] trackingString, autoupString;
	private ImageView trackingNone, trackingOnFoot, trackingAlways;
	private ImageView autoupDisabled, autoupWifi, autoupAlways;
	private TextView autoupDesc, trackDesc, signInNoConnection;
	private Switch switchNoise;

	private ImageView mTrackingSelected, mAutoupSelected;

	private SignInButton signInButton;
	private Button signOutButton;
	private Signin signin;

	private ColorStateList mSelectedState;
	private ColorStateList mDefaultState;

	private void updateTracking(int select) {
		Context context = getContext();
		Preferences.get(context).edit().putInt(Preferences.BACKGROUND_TRACKING, select).apply();
		ImageView selected;
		switch (select) {
			case 0:
				selected = trackingNone;
				break;
			case 1:
				selected = trackingOnFoot;
				break;
			case 2:
				selected = trackingAlways;
				break;
			default:
				return;
		}
		FirebaseAssist.updateValue(context, FirebaseAssist.autoUploadString, trackingString[select]);
		trackDesc.setText(trackingString[select]);
		if (mTrackingSelected != null)
			mTrackingSelected.setImageTintList(mDefaultState);
		selected.setImageTintList(mSelectedState);
		mTrackingSelected = selected;
	}

	private void updateAutoup(int select) {
		Context context = getContext();
		Preferences.get(context).edit().putInt(Preferences.AUTO_UPLOAD, select).apply();
		ImageView selected;
		switch (select) {
			case 0:
				selected = autoupDisabled;
				break;
			case 1:
				selected = autoupWifi;
				break;
			case 2:
				selected = autoupAlways;
				break;
			default:
				return;
		}
		FirebaseAssist.updateValue(context, FirebaseAssist.autoUploadString, autoupString[select]);

		autoupDesc.setText(autoupString[select]);
		if (mAutoupSelected != null)
			mAutoupSelected.setImageTintList(mDefaultState);
		selected.setImageTintList(mSelectedState);
		mAutoupSelected = selected;
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		final View rootView = inflater.inflate(R.layout.fragment_settings, container, false);
		final Context context = getContext();
		final Resources resources = getResources();
		final SharedPreferences sharedPreferences = Preferences.get(getContext());

		try {
			((TextView) rootView.findViewById(R.id.versionNum)).setText(context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName);
		} catch (Exception e) {
			Log.d(TAG, "Failed to set version");
		}

		mSelectedState = ResourcesCompat.getColorStateList(resources, R.color.selected_value, context.getTheme());
		mDefaultState = ResourcesCompat.getColorStateList(resources, R.color.default_value, context.getTheme());

		trackingString = resources.getStringArray(R.array.background_tracking_options);
		autoupString = resources.getStringArray(R.array.automatic_upload_options);

		autoupDesc = (TextView) rootView.findViewById(R.id.autoupload_description);
		trackDesc = (TextView) rootView.findViewById(R.id.tracking_description);

		trackingNone = (ImageView) rootView.findViewById(R.id.tracking_none);
		trackingNone.setOnClickListener(v -> updateTracking(0));
		trackingOnFoot = (ImageView) rootView.findViewById(R.id.tracking_onfoot);
		trackingOnFoot.setOnClickListener(v -> updateTracking(1));
		trackingAlways = (ImageView) rootView.findViewById(R.id.tracking_always);
		trackingAlways.setOnClickListener(v -> updateTracking(2));

		autoupDisabled = (ImageView) rootView.findViewById(R.id.autoupload_disabled);
		autoupDisabled.setOnClickListener(v -> updateAutoup(0));
		autoupWifi = (ImageView) rootView.findViewById(R.id.autoupload_wifi);
		autoupWifi.setOnClickListener(v -> updateAutoup(1));
		autoupAlways = (ImageView) rootView.findViewById(R.id.autoupload_always);
		autoupAlways.setOnClickListener(v -> updateAutoup(2));

		updateTracking(sharedPreferences.getInt(Preferences.BACKGROUND_TRACKING, 1));
		updateAutoup(sharedPreferences.getInt(Preferences.AUTO_UPLOAD, 1));

		signInButton = (SignInButton) rootView.findViewById(R.id.sign_in_button);
		signOutButton = (Button) rootView.findViewById(R.id.sign_out_button);
		signInNoConnection = (TextView) rootView.findViewById(R.id.sign_in_no_connection);

		rootView.findViewById(R.id.other_clear).setOnClickListener(v -> {
			AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(context, R.style.AlertDialog);
			alertDialogBuilder
					.setPositiveButton(getResources().getText(R.string.alert_clear_confirm), (dialog, which) -> DataStore.clearAllData())
					.setNegativeButton(getResources().getText(R.string.alert_clear_cancel), (dialog, which) -> {
					})
					.setMessage(getResources().getText(R.string.alert_clear_text));

			alertDialogBuilder.create().show();
		});

		Button mapOverlayButton = (Button) rootView.findViewById(R.id.setting_map_overlay_button);
		mapOverlayButton.setEnabled(false);

		NetworkLoader.load(Network.URL_MAPS_AVAILABLE, Assist.DAY_IN_MINUTES, context, Preferences.AVAILABLE_MAPS, MapLayer[].class, (state, layerArray) -> {
			Activity activity = getActivity();
			if (activity != null) {
				if (layerArray != null && layerArray.length > 0) {
					SharedPreferences sp = Preferences.get(context);
					String defaultOverlay = sp.getString(Preferences.DEFAULT_MAP_OVERLAY, layerArray[0].name);
					int index = MapLayer.indexOf(layerArray, defaultOverlay);
					final int selectIndex = index == -1 ? 0 : index;
					if (index == -1)
						sp.edit().putString(Preferences.DEFAULT_MAP_OVERLAY, layerArray[0].name).apply();

					CharSequence[] items = new CharSequence[layerArray.length];
					for (int i = 0; i < layerArray.length; i++)
						items[i] = layerArray[i].name;
					activity.runOnUiThread(() -> {
						final ArrayAdapter<String> adapter = new ArrayAdapter<>(context, R.layout.spinner_item, MapLayer.toStringArray(layerArray));
						adapter.setDropDownViewResource(R.layout.spinner_item);
						mapOverlayButton.setEnabled(true);
						mapOverlayButton.setText(items[selectIndex]);
						mapOverlayButton.setOnClickListener(v -> {
							String ov = sp.getString(Preferences.DEFAULT_MAP_OVERLAY, layerArray[0].name);
							int in = MapLayer.indexOf(layerArray, ov);
							int selectIn = in == -1 ? 0 : in;

							AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(context, R.style.AlertDialog);
							alertDialogBuilder
									.setTitle(getString(R.string.settings_default_map_overlay))
									.setSingleChoiceItems(items, selectIn, (dialog, which) -> {
										Preferences.get(context).edit().putString(Preferences.DEFAULT_MAP_OVERLAY, adapter.getItem(which)).apply();
										mapOverlayButton.setText(items[which]);
										dialog.dismiss();
									})
									.setNegativeButton(R.string.cancel, (dialog, which) -> {
									});

							alertDialogBuilder.create().show();
						});
					});
				} else {
					activity.runOnUiThread(() -> mapOverlayButton.setEnabled(false));
				}
			}
		});

		setSwitchChangeListener(context, Preferences.TRACKING_WIFI_ENABLED, (Switch) rootView.findViewById(R.id.switchTrackWifi), true, null);
		setSwitchChangeListener(context, Preferences.TRACKING_CELL_ENABLED, (Switch) rootView.findViewById(R.id.switchTrackCell), true, null);

		switchNoise = (Switch) rootView.findViewById(R.id.switchTrackNoise);
		switchNoise.setChecked(Preferences.get(context).getBoolean(Preferences.TRACKING_NOISE_ENABLED, false));
		switchNoise.setOnCheckedChangeListener((CompoundButton compoundButton, boolean b) -> {
			if (b && Build.VERSION.SDK_INT > 22 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
				getActivity().requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_PERMISSIONS_MICROPHONE);
			else
				Preferences.get(context).edit().putBoolean(Preferences.TRACKING_NOISE_ENABLED, b).apply();
		});

		setSwitchChangeListener(context, Preferences.UPLOAD_NOTIFICATIONS_ENABLED, (Switch) rootView.findViewById(R.id.switchNotificationsUpload), true, (b) -> FirebaseAssist.updateValue(context, FirebaseAssist.uploadNotificationString, Boolean.toString(b)));
		setSwitchChangeListener(context, Preferences.STOP_TILL_RECHARGE, (Switch) rootView.findViewById(R.id.switchDisableTrackingTillRecharge), false, (b) -> {
			if (b) {
				Bundle bundle = new Bundle();
				bundle.putString(FirebaseAssist.PARAM_SOURCE, "settings");
				FirebaseAnalytics.getInstance(context).logEvent(FirebaseAssist.STOP_TILL_RECHARGE_EVENT, bundle);
				if (TrackerService.isRunning())
					context.stopService(new Intent(context, TrackerService.class));
			}
		});

		TextView valueAutoUploadAt = (TextView) rootView.findViewById(R.id.settings_autoupload_at_value);
		SeekBar seekAutoUploadAt = (SeekBar) rootView.findViewById(R.id.settings_autoupload_at_seekbar);

		final int MIN_UPLOAD_VALUE = 1;

		seekAutoUploadAt.incrementProgressBy(1);
		seekAutoUploadAt.setMax(10 - MIN_UPLOAD_VALUE);
		seekAutoUploadAt.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				valueAutoUploadAt.setText(getString(R.string.settings_autoupload_at_value, progress + MIN_UPLOAD_VALUE));
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {

			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
				Preferences.get(context).edit().putInt(Preferences.AUTO_UPLOAD_AT_MB, seekBar.getProgress() + MIN_UPLOAD_VALUE).apply();
			}
		});
		seekAutoUploadAt.setProgress(Preferences.get(context).getInt(Preferences.AUTO_UPLOAD_AT_MB, Preferences.DEFAULT_AUTO_UPLOAD_AT_MB) - MIN_UPLOAD_VALUE);

		if (Assist.hasNetwork()) {
			signin = Signin.getInstance(getActivity());
			signin.manageButtons(signInButton, signOutButton);
		} else
			signInNoConnection.setVisibility(View.VISIBLE);


		//Dev stuff
		rootView.findViewById(R.id.dev_button_cache_clear).setOnClickListener((v) -> {
			AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(context, R.style.AlertDialog);
			alertDialogBuilder
					.setPositiveButton(getResources().getText(R.string.alert_confirm_generic), (dialog, which) -> {
						SnackMaker snackMaker = new SnackMaker(getActivity());
						File[] files = getContext().getFilesDir().listFiles();
						for (File file : files) {
							String fileName = file.getName();
							if (!fileName.startsWith(DataStore.DATA_FILE)) {
								while (!file.delete()) {
									try {
										Thread.sleep(25);
									} catch (InterruptedException e) {
										FirebaseCrash.report(e);
									}
								}
								snackMaker.showSnackbar("Deleted " + fileName);
							}
						}
					})
					.setNegativeButton(getResources().getText(R.string.alert_confirm_generic_cancel), (dialog, which) -> {
					})
					.setMessage(getResources().getText(R.string.alert_confirm_generic_confirm));

			alertDialogBuilder.create().show();
		});

		rootView.findViewById(R.id.dev_button_browse_files).setOnClickListener(v -> {
			File[] files = getContext().getFilesDir().listFiles();
			String[] fileNames = new String[files.length];
			for (int i = 0; i < files.length; i++) {
				fileNames[i] = files[i].getName();
			}
			AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(context, R.style.AlertDialog);
			alertDialogBuilder
					.setTitle(getString(R.string.settings_default_map_overlay))
					.setItems(fileNames, (dialog, which) -> {
						Intent intent = new Intent(getActivity(), DebugFileActivity.class);
						intent.putExtra("fileName", fileNames[which]);
						startActivity(intent);
					})
					.setNegativeButton(R.string.cancel, (dialog, which) -> {
					});

			alertDialogBuilder.create().show();
		});

		return rootView;
	}

	private void setSwitchChangeListener(@NonNull final Context context, @NonNull final String name, Switch s, final boolean defaultState, @Nullable final IValueCallback<Boolean> callback) {
		s.setChecked(Preferences.get(context).getBoolean(name, defaultState));
		s.setOnCheckedChangeListener((CompoundButton compoundButton, boolean b) -> {
			Preferences.get(context).edit().putBoolean(name, b).apply();
			if (callback != null)
				callback.callback(b);
		});
	}

	@Override
	public Failure<String> onEnter(@NonNull FragmentActivity activity, @NonNull FloatingActionButton fabOne, @NonNull FloatingActionButton fabTwo) {
		return new Failure<>();
	}

	@Override
	public void onLeave(@NonNull FragmentActivity activity) {
		if (signin != null) {
			signin.forgetButtons();
			signin = null;
		}
	}

	@Override
	public void onPermissionResponse(int requestCode, boolean success) {
		switch (requestCode) {
			case REQUEST_CODE_PERMISSIONS_MICROPHONE:
				if (success)
					Preferences.get(getContext()).edit().putBoolean(Preferences.TRACKING_NOISE_ENABLED, true).apply();
				else
					switchNoise.setChecked(false);
				break;
			default:
				throw new UnsupportedOperationException("Permissions with request code " + requestCode + " has no defined behavior");
		}
	}

	@Override
	public void onHomeAction() {
		View v = getView();
		if (v != null) {
			Assist.verticalSmoothScrollTo((ScrollView) v.findViewById(R.id.settings_scrollbar), 0, 500);
		}
	}

}
