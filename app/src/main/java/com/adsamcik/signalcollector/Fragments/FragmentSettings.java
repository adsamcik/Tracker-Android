package com.adsamcik.signalcollector.fragments;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.res.ResourcesCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Network;
import com.adsamcik.signalcollector.utility.NetworkLoader;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.utility.DataStore;
import com.adsamcik.signalcollector.utility.Success;
import com.adsamcik.signalcollector.interfaces.ITabFragment;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.SigninController;
import com.google.android.gms.common.SignInButton;

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
	private SigninController signinController;

	private ColorStateList mSelectedState;
	private ColorStateList mDefaultState;

	private void updateTracking(int select) {
		Preferences.get(getContext()).edit().putInt(Preferences.BACKGROUND_TRACKING, select).apply();
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
		trackDesc.setText(trackingString[select]);
		if (mTrackingSelected != null)
			mTrackingSelected.setImageTintList(mDefaultState);
		selected.setImageTintList(mSelectedState);
		mTrackingSelected = selected;
	}

	private void updateAutoup(int select) {
		Preferences.get(getContext()).edit().putInt(Preferences.AUTO_UPLOAD, select).apply();
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

		Spinner mapOverlaySpinner = (Spinner) rootView.findViewById(R.id.setting_map_overlay_spinner);

		NetworkLoader.loadStringArray(Network.URL_MAPS_AVAILABLE, Assist.DAY_IN_MINUTES, context, Preferences.AVAILABLE_MAPS, stringArray -> {
			if (stringArray.size() > 0) {
				SharedPreferences sp = Preferences.get(context);
				final String defaultOverlay = sp.getString(Preferences.DEFAULT_MAP_OVERLAY, stringArray.get(0));
				int index = stringArray.indexOf(defaultOverlay);
				final int selectIndex = index == -1 ? 0 : index;
				if (index == -1)
					sp.edit().putString(Preferences.DEFAULT_MAP_OVERLAY, stringArray.get(0)).apply();

				getActivity().runOnUiThread(() -> {
					final ArrayAdapter<String> adapter = new ArrayAdapter<>(context, R.layout.spinner_item, stringArray);
					adapter.setDropDownViewResource(R.layout.spinner_item);
					mapOverlaySpinner.setAdapter(adapter);
					mapOverlaySpinner.setSelection(selectIndex);
					mapOverlaySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
						@Override
						public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
							Preferences.get(context).edit().putString(Preferences.DEFAULT_MAP_OVERLAY, adapter.getItem(i)).apply();
						}

						@Override
						public void onNothingSelected(AdapterView<?> adapterView) {

						}
					});
				});
			} else {
				getActivity().runOnUiThread(() -> mapOverlaySpinner.setEnabled(false));
			}
		});

		setSwitchChangeListener(context, Preferences.TRACKING_WIFI_ENABLED, (Switch) rootView.findViewById(R.id.switchTrackWifi), true);
		setSwitchChangeListener(context, Preferences.TRACKING_CELL_ENABLED, (Switch) rootView.findViewById(R.id.switchTrackCell), true);

		switchNoise = (Switch) rootView.findViewById(R.id.switchTrackNoise);
		switchNoise.setChecked(Preferences.get(context).getBoolean(Preferences.TRACKING_NOISE_ENABLED, false));
		switchNoise.setOnCheckedChangeListener((CompoundButton compoundButton, boolean b) -> {
			if (b && Build.VERSION.SDK_INT > 22 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
				getActivity().requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_PERMISSIONS_MICROPHONE);
			else
				Preferences.get(context).edit().putBoolean(Preferences.TRACKING_NOISE_ENABLED, b).apply();
		});

		setSwitchChangeListener(context, Preferences.UPLOAD_NOTIFICATIONS_ENABLED, (Switch) rootView.findViewById(R.id.switchNotifications), true);

		return rootView;
	}

	private void setSwitchChangeListener(final Context context, final String name, Switch s, final boolean defaultState) {
		s.setChecked(Preferences.get(context).getBoolean(name, defaultState));
		s.setOnCheckedChangeListener((CompoundButton compoundButton, boolean b) -> Preferences.get(context).edit().putBoolean(name, b).apply());
	}

	@Override
	public Success<String> onEnter(FragmentActivity activity, FloatingActionButton fabOne, FloatingActionButton fabTwo) {
		if (Assist.hasNetwork()) {
			signinController = SigninController.getInstance(getActivity());
			signinController.manageButtons(signInButton, signOutButton);
		} else
			signInNoConnection.setVisibility(View.VISIBLE);
		return new Success<>();
	}

	@Override
	public void onLeave() {
		signInButton.setVisibility(View.GONE);
		signInNoConnection.setVisibility(View.GONE);
		signOutButton.setVisibility(View.GONE);
		signinController.forgetButtons();
		signinController = null;
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
	public ITabFragment newInstance() {
		return new FragmentSettings();
	}
}
