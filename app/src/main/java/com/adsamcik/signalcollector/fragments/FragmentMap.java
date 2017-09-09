package com.adsamcik.signalcollector.fragments;


import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Address;
import android.location.Criteria;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import com.adsamcik.signalcollector.utility.Signin;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Failure;
import com.adsamcik.signalcollector.data.MapLayer;
import com.adsamcik.signalcollector.network.NetworkLoader;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.utility.FabMenu;
import com.adsamcik.signalcollector.network.Network;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.interfaces.ITabFragment;
import com.adsamcik.signalcollector.network.SignalsTileProvider;
import com.adsamcik.signalcollector.utility.SnackMaker;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.UiSettings;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;

import java.io.IOException;
import java.util.List;

import static com.adsamcik.signalcollector.utility.Constants.DAY_IN_MINUTES;

public class FragmentMap extends Fragment implements OnMapReadyCallback, ITabFragment {
	private static final int MAX_ZOOM = 17;
	private static final int PERMISSION_LOCATION_CODE = 200;

	private static final String TAG = "SignalsMap";
	private UpdateLocationListener locationListener;
	private String type = null;
	private GoogleMap map;
	private SignalsTileProvider tileProvider;
	private LocationManager locationManager;
	private TileOverlay activeOverlay;

	private EditText searchText;

	private View view;

	private Circle userRadius;
	private Marker userCenter;

	private FragmentActivity activity;
	private FloatingActionButton fabTwo, fabOne;
	private FabMenu menu;

	boolean showFabTwo;

	@Override
	public void onPermissionResponse(int requestCode, boolean success) {
		if (requestCode == PERMISSION_LOCATION_CODE && success && getActivity() != null) {
			FragmentTransaction fragmentTransaction = getActivity().getSupportFragmentManager().beginTransaction();
			FragmentMap newFrag = new FragmentMap();
			fragmentTransaction.replace(R.id.container, newFrag, getString(R.string.menu_map));
			newFrag.onEnter(activity, fabOne, fabTwo);
			fragmentTransaction.commit();
		}
	}

	@Override
	public void onHomeAction() {

	}

	/**
	 * Check if permission to access fine location is granted
	 * If not and is android 6 or newer, than it prompts you to enable it
	 *
	 * @return is permission available atm
	 */
	private boolean checkLocationPermission(Context context, boolean request) {
		if (context == null)
			return false;
		if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
			return true;
		else if (request && Build.VERSION.SDK_INT >= 23)
			getActivity().requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_LOCATION_CODE);
		return false;
	}

	/**
	 * This function should be called when fragment is left
	 */
	public void onLeave(@NonNull FragmentActivity activity) {
		if (hasPermissions) {
			if (locationManager != null)
				locationManager.removeUpdates(locationListener);
			locationListener.cleanup();

			if (menu != null)
				menu.hideAndDestroy(activity);

			fabOne.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.text_primary)));
		}
	}

	/**
	 * Initializes fabs for Map fragment
	 *
	 * @param fabOne fabOne (lower)
	 * @param fabTwo fabTwo (above fabOne)
	 */
	@NonNull
	public Failure<String> onEnter(@NonNull FragmentActivity activity, @NonNull FloatingActionButton fabOne, @NonNull FloatingActionButton fabTwo) {
		this.fabTwo = fabTwo;
		this.fabOne = fabOne;
		this.activity = activity;

		if (!Assist.isPlayServiceAvailable(activity))
			return new Failure<>(activity.getString(R.string.error_play_services_not_available));

		initializeLocationListener(activity);
		locationListener.setFAB(fabOne, activity);

		fabOne.show();
		fabOne.setOnClickListener(v -> {
			if (checkLocationPermission(activity, true) && map != null)
				locationListener.onMyPositionFabClick();
		});


		//fabTwo.setOnClickListener(v -> changeMapOverlay(typeIndex + 1 == availableTypes.length ? 0 : typeIndex + 1, fabTwo));
		fabTwo.setOnClickListener(v -> menu.show(activity));

		return new Failure<>();
	}

	boolean hasPermissions = false;

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		activity = activity == null ? getActivity() : activity;
		if (Assist.isPlayServiceAvailable(activity) && container != null && hasPermissions)
			view = inflater.inflate(R.layout.fragment_map, container, false);
		else {
			view = inflater.inflate(R.layout.layout_error, container, false);
			((TextView) view.findViewById(R.id.activity_error_text)).setText(hasPermissions ? R.string.error_play_services_not_available : R.string.error_missing_permission);
			return view;
		}

		menu = new FabMenu((ViewGroup) container.getParent(), fabTwo, activity);
		menu.setCallback(this::changeMapOverlay);

		NetworkLoader.request(Network.URL_MAPS_AVAILABLE, DAY_IN_MINUTES, activity, Preferences.PREF_AVAILABLE_MAPS, MapLayer[].class, (state, layerArray) -> {
			if (fabTwo != null && layerArray != null) {
				String savedOverlay = Preferences.get(activity).getString(Preferences.PREF_DEFAULT_MAP_OVERLAY, layerArray[0].name);
				if (!MapLayer.contains(layerArray, savedOverlay)) {
					savedOverlay = layerArray[0].name;
					Preferences.get(activity).edit().putString(Preferences.PREF_DEFAULT_MAP_OVERLAY, savedOverlay).apply();
				}

				final String defaultOverlay = savedOverlay;
				activity.runOnUiThread(() -> {
					if (menu != null) {
						if (menu.getItemCount() == 0)
							changeMapOverlay(defaultOverlay);

						if (layerArray.length > 0) {
							for (MapLayer layer : layerArray)
								menu.addItem(layer.name, activity);
						}
						if (fabOne.isShown())
							fabTwo.show();
						showFabTwo = true;
					}
				});
			}
		});

		Signin.getUserDataAsync(activity, u -> {
			if (fabTwo != null && u != null)
				activity.runOnUiThread(() -> {
					if (menu != null)
						menu.addItem(activity.getString(R.string.map_personal), activity);
				});
		});

		searchText = view.findViewById(R.id.map_search);
		searchText.setOnEditorActionListener((v, actionId, event) -> {
			Geocoder geocoder = new Geocoder(getContext());
			try {
				List<Address> addresses = geocoder.getFromLocationName(v.getText().toString(), 1);
				if (addresses != null && addresses.size() > 0) {
					if (map != null && locationListener != null) {
						Address address = addresses.get(0);
						locationListener.stopUsingUserPosition(true);
						locationListener.animateToPositionZoom(new LatLng(address.getLatitude(), address.getLongitude()), 13);
					}
				}

			} catch (IOException e) {
				new SnackMaker(view).showSnackbar(R.string.error_general);
			}
			return true;
		});

		return view;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		hasPermissions = checkLocationPermission(getContext(), true);
		if (hasPermissions) {
			SupportMapFragment mapFragment = SupportMapFragment.newInstance();
			FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
			fragmentTransaction.add(R.id.container_map, mapFragment);
			fragmentTransaction.commit();
			mapFragment.getMapAsync(this);
			if (fabOne != null && fabTwo != null) {
				fabOne.setImageResource(R.drawable.ic_gps_fixed_black_24dp);
				fabTwo.setImageResource(R.drawable.ic_layers_black_24dp);
			}
		} else {
			if (fabOne != null && fabTwo != null) {
				fabOne.hide();
				fabTwo.hide();
			}
		}
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		map = null;
		view = null;
		menu = null;
	}

	/**
	 * Changes overlay of the map
	 *
	 * @param type exact case-sensitive name of the overlay
	 */
	private void changeMapOverlay(@NonNull String type) {
		if (map != null) {
			if ((!type.equals(this.type) || activeOverlay == null)) {
				if (activeOverlay != null)
					activeOverlay.remove();

				if (type.equals(getString(R.string.map_personal)))
					tileProvider.setTypePersonal();
				else
					tileProvider.setType(type);

				this.type = type;
				activeOverlay = map.addTileOverlay(new TileOverlayOptions().tileProvider(tileProvider));
			}
		} else this.type = type;
	}

	private void initializeLocationListener(@NonNull Context context) {
		if (locationListener == null) {
			SensorManager sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
			assert sensorManager != null;
			locationListener = new UpdateLocationListener(sensorManager);
		}
	}

	@Override
	public void onMapReady(GoogleMap map) {
		this.map = map;
		userRadius = null;
		userCenter = null;
		Context c = getContext();
		if (c == null)
			return;
		map.setMapStyle(MapStyleOptions.loadRawResourceStyle(c, R.raw.map_style));

		//does not work well with bearing. Known bug in Google maps api since 2014.
		//map.setPadding(0, Assist.dpToPx(c, 48 + 40 + 8), 0, 0);
		tileProvider = new SignalsTileProvider(c, MAX_ZOOM);

		initializeLocationListener(c);

		map.setMaxZoomPreference(MAX_ZOOM);
		if (checkLocationPermission(c, false)) {
			locationListener.setFollowMyPosition(true, c);
			if (locationManager == null)
				locationManager = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
			assert locationManager != null;
			Location l = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
			if (l != null) {
				CameraPosition cp = CameraPosition.builder().target(new LatLng(l.getLatitude(), l.getLongitude())).zoom(16).build();
				map.moveCamera(CameraUpdateFactory.newCameraPosition(cp));
				locationListener.targetPosition = cp.target;
				DrawUserPosition(cp.target, l.getAccuracy());
			}
		}

		if (type != null)
			changeMapOverlay(type);

		map.setOnMapClickListener(latLng -> {
			if (searchText.hasFocus()) {
				Assist.hideSoftKeyboard(activity, searchText);
				searchText.clearFocus();
			} else if (searchText.getVisibility() == View.VISIBLE) {
				searchText.setVisibility(View.INVISIBLE);
				if (showFabTwo)
					fabTwo.hide();
				fabOne.hide();
			} else {
				searchText.setVisibility(View.VISIBLE);
				if (showFabTwo)
					fabTwo.show();
				fabOne.show();
			}
		});

		UiSettings uiSettings = map.getUiSettings();
		uiSettings.setMapToolbarEnabled(false);
		uiSettings.setIndoorLevelPickerEnabled(false);
		uiSettings.setCompassEnabled(false);

		locationListener.registerMap(map);

		if (locationManager == null)
			locationManager = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
		locationManager.requestLocationUpdates(1, 5, new Criteria(), locationListener, Looper.myLooper());
	}

	/**
	 * Draws user accuracy radius and location
	 * Is automatically initialized if no circle exists
	 *
	 * @param latlng   Latitude and longitude
	 * @param accuracy Accuracy
	 */
	private void DrawUserPosition(LatLng latlng, float accuracy) {
		if (map == null)
			return;
		if (userRadius == null) {
			Context c = getContext();
			userRadius = map.addCircle(new CircleOptions()
					.fillColor(ContextCompat.getColor(c, R.color.color_user_accuracy))
					.center(latlng)
					.radius(accuracy)
					.zIndex(100)
					.strokeWidth(0));

			userCenter = map.addMarker(new MarkerOptions()
					.flat(true)
					.position(latlng)
					.icon(BitmapDescriptorFactory.fromResource(R.mipmap.ic_user_location))
					.anchor(0.5f, 0.5f)
			);
		} else {
			userRadius.setCenter(latlng);
			userRadius.setRadius(accuracy);
			userCenter.setPosition(latlng);
		}
	}

	private class UpdateLocationListener implements LocationListener, SensorEventListener {
		private boolean followMyPosition = false;
		boolean useGyroscope = false;

		private Sensor rotationVector;
		private SensorManager sensorManager;

		private LatLng lastUserPos;
		private LatLng targetPosition;
		private float targetTilt;
		private float targetBearing;
		private float targetZoom;

		private FloatingActionButton fab;

		public UpdateLocationListener(@NonNull SensorManager sensorManager) {
			rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
			this.sensorManager = sensorManager;
		}

		public void registerMap(GoogleMap map) {
			map.setOnCameraMoveStartedListener(cameraChangeListener);
			CameraPosition cameraPosition = map.getCameraPosition();
			targetPosition = cameraPosition.target == null ? new LatLng(0, 0) : cameraPosition.target;
			targetTilt = cameraPosition.tilt;
			targetBearing = cameraPosition.bearing;
			targetZoom = cameraPosition.zoom;
		}

		public void setFAB(@NonNull FloatingActionButton fab, @NonNull Context context) {
			this.fab = fab;
			setFollowMyPosition(followMyPosition, context);
		}

		public void setFollowMyPosition(boolean value, @NonNull Context context) {
			this.followMyPosition = value;
			if (fab != null && getContext() != null) {
				if (followMyPosition)
					fab.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_accent)));
				else
					fab.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_primary)));
			}
		}

		private void stopUsingGyroscope(boolean returnToDefault) {
			useGyroscope = false;
			sensorManager.unregisterListener(this, rotationVector);
			targetBearing = 0;
			targetTilt = 0;
			if (returnToDefault)
				animateTo(targetPosition, targetZoom, 0, 0, DURATION_SHORT);
		}

		public void stopUsingUserPosition(boolean returnToDefault) {
			if (followMyPosition) {
				setFollowMyPosition(false, getContext());
				if (useGyroscope) {
					stopUsingGyroscope(returnToDefault);
					fab.setImageResource(R.drawable.ic_gps_fixed_black_24dp);
				}
			}
		}

		private final GoogleMap.OnCameraMoveStartedListener cameraChangeListener = i -> {
			if (followMyPosition && i == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE)
				stopUsingUserPosition(true);
		};

		private final int DURATION_STANDARD = 1000;
		private final int DURATION_SHORT = 200;

		@Override
		public void onLocationChanged(Location location) {
			lastUserPos = new LatLng(location.getLatitude(), location.getLongitude());
			DrawUserPosition(lastUserPos, location.getAccuracy());
			if (followMyPosition && map != null)
				moveTo(lastUserPos);
		}

		private void animateToPositionZoom(LatLng position, float zoom) {
			targetPosition = position;
			targetZoom = zoom;
			animateTo(position, zoom, targetTilt, targetBearing, DURATION_STANDARD);
		}

		private void animateToBearing(float bearing) {
			animateTo(targetPosition, targetZoom, targetTilt, bearing, DURATION_SHORT);
			targetBearing = bearing;
		}

		private void animateToTilt(float tilt) {
			targetTilt = tilt;
			animateTo(targetPosition, targetZoom, tilt, targetBearing, DURATION_SHORT);
		}

		private void animateTo(LatLng position, float zoom, float tilt, float bearing, int duration) {
			CameraPosition.Builder builder = new CameraPosition.Builder(map.getCameraPosition()).target(position).zoom(zoom).tilt(tilt).bearing(bearing);
			map.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()), duration, null);
		}

		private void onMyPositionFabClick() {
			if (followMyPosition) {
				if (useGyroscope) {
					fab.setImageResource(R.drawable.ic_gps_fixed_black_24dp);
					stopUsingGyroscope(true);
				} else {
					useGyroscope = true;
					sensorManager.registerListener(this, rotationVector,
							SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI);
					animateToTilt(45);
					fab.setImageResource(R.drawable.ic_compass);
				}
			} else {
				setFollowMyPosition(true, getContext());
			}

			if (lastUserPos != null)
				moveTo(lastUserPos);
		}

		private void moveTo(@NonNull LatLng latlng) {
			float zoom = map.getCameraPosition().zoom;
			animateToPositionZoom(latlng, zoom < 16 ? 16 : zoom > 17 ? 17 : zoom);
		}

		@Override
		public void onStatusChanged(String provider, int status, Bundle extras) {

		}

		@Override
		public void onProviderEnabled(String provider) {

		}

		@Override
		public void onProviderDisabled(String provider) {

		}

		private void cleanup() {
			if (map != null)
				map.setOnMyLocationButtonClickListener(null);
		}

		float prevRotation;

		private void updateRotation(int rotation) {
			if (map != null && targetPosition != null && prevRotation != rotation) {
				animateToBearing(rotation);
			}
		}

		float[] orientation = new float[3];
		float[] rMat = new float[9];

		@Override
		public void onSensorChanged(SensorEvent event) {
			if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
				// calculate th rotation matrix
				SensorManager.getRotationMatrixFromVector(rMat, event.values);
				// get the azimuth value (orientation[0]) in degree
				updateRotation((int) (Math.toDegrees(SensorManager.getOrientation(rMat, orientation)[0]) + 360) % 360);
			}
		}

		@Override
		public void onAccuracyChanged(Sensor sensor, int accuracy) {

		}
	}

}
