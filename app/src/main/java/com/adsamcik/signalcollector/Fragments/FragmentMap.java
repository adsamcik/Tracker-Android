package com.adsamcik.signalcollector.fragments;


import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
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

import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Failure;
import com.adsamcik.signalcollector.utility.MapLayer;
import com.adsamcik.signalcollector.utility.NetworkLoader;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.utility.FabMenu;
import com.adsamcik.signalcollector.utility.Network;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.interfaces.ITabFragment;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
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
import com.google.android.gms.maps.model.TileProvider;
import com.google.android.gms.maps.model.UrlTileProvider;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class FragmentMap extends Fragment implements OnMapReadyCallback, ITabFragment {
	private static final int MAX_ZOOM = 17;
	private static final String TAG = "SignalsMap";
	private String type = null;
	private boolean initialized = false;
	private GoogleMap map;
	private TileProvider tileProvider;

	private LocationManager locationManager;
	private final UpdateLocationListener locationListener = new UpdateLocationListener();

	private TileOverlay activeOverlay;

	private View view;

	private Circle userRadius;
	private Marker userCenter;

	private FabMenu menu;

	@Override
	public void onPermissionResponse(int requestCode, boolean success) {

	}

	/**
	 * Check if permission to access fine location is granted
	 * If not and is android 6 or newer, than it prompts you to enable it
	 *
	 * @return is permission available atm
	 */
	private boolean checkLocationPermission(Context context, boolean request) {
		if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
			return true;
		else if (request)
			requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 200);
		return false;
	}

	/**
	 * This function should be called when fragment is left
	 */
	public void onLeave() {
		if (locationManager != null && checkLocationPermission(getContext(), false))
			locationManager.removeUpdates(locationListener);
		locationListener.cleanup();
		menu.hide();

	}

	@Override
	public ITabFragment newInstance() {
		return new FragmentMap();
	}

	/**
	 * Initializes fabs for Map fragment
	 *
	 * @param fabOne fabOne (lower)
	 * @param fabTwo fabTwo (above fabOne)
	 */
	public Failure<String> onEnter(FragmentActivity activity, FloatingActionButton fabOne, FloatingActionButton fabTwo) {
		if (!Assist.isPlayServiceAvailable(activity))
			return new Failure<>("Play services are not available");
		if (checkLocationPermission(activity, true)) {
			if (locationManager == null)
				locationManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
			locationManager.requestLocationUpdates(1, 5, new Criteria(), locationListener, Looper.myLooper());
		} else
			return new Failure<>("App does not have required permissions.");

		fabOne.show();
		fabOne.setImageResource(R.drawable.ic_gps_fixed_black_24dp);
		fabOne.setOnClickListener(v -> {
			if (checkLocationPermission(activity, true))
				locationListener.moveToMyPosition();
		});

		menu.clear(activity);


		NetworkLoader.load(Network.URL_MAPS_AVAILABLE, Assist.DAY_IN_MINUTES, activity, Preferences.AVAILABLE_MAPS, MapLayer[].class, layerArray -> {
			if (fabTwo != null && layerArray != null) {
				String savedOverlay = Preferences.get(activity).getString(Preferences.DEFAULT_MAP_OVERLAY, layerArray[0].name);
				if (!MapLayer.contains(layerArray, savedOverlay)) {
					savedOverlay = layerArray[0].name;
					Preferences.get(activity).edit().putString(Preferences.DEFAULT_MAP_OVERLAY, savedOverlay).apply();
				}

				final String defaultOverlay = savedOverlay;
				activity.runOnUiThread(() -> {
					changeMapOverlay(defaultOverlay);
					menu.clear(activity);
					if (layerArray.length > 0) {
						for (MapLayer layer : layerArray)
							menu.addItem(layer.name, activity);
					}
					fabTwo.show();
				});
			}
		});
		menu.setFab(fabTwo);
		fabTwo.setImageResource(R.drawable.ic_layers_black_24dp);
		//fabTwo.setOnClickListener(v -> changeMapOverlay(typeIndex + 1 == availableTypes.length ? 0 : typeIndex + 1, fabTwo));
		fabTwo.setOnClickListener(v -> menu.show(activity));
		menu.setCallback(this::changeMapOverlay);

		if (!initialized) {
			//SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.map);
			SupportMapFragment mapFragment = SupportMapFragment.newInstance();
			FragmentTransaction fragmentTransaction = activity.getSupportFragmentManager().beginTransaction();
			fragmentTransaction.add(R.id.MapLayout, mapFragment);
			fragmentTransaction.commit();
			mapFragment.getMapAsync(this);
			initialized = true;
		}


		return new Failure<>();
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		if (view != null) {
			ViewGroup parent = (ViewGroup) view.getParent();
			if (parent != null)
				parent.removeView(view);
		}

		if (Assist.isPlayServiceAvailable(getContext()))
			view = inflater.inflate(R.layout.fragment_map, container, false);
		else
			view = inflater.inflate(R.layout.no_play_services, container, false);

		Context c = getContext();
		assert container != null;
		menu = new FabMenu((ViewGroup) container.getParent(), c);
		return view;
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		initialized = false;
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
				activeOverlay = map.addTileOverlay(new TileOverlayOptions().tileProvider(tileProvider));
				this.type = type;
			}
		} else this.type = type;
	}

	@Override
	public void onMapReady(GoogleMap map) {
		this.map = map;
		userRadius = null;
		userCenter = null;
		map.setMapStyle(MapStyleOptions.loadRawResourceStyle(getContext(), R.raw.map_style));

		tileProvider = new UrlTileProvider(256, 256) {
			@Override
			public URL getTileUrl(int x, int y, int zoom) {
				String s = String.format(Locale.ENGLISH, Network.URL_TILES, zoom, x, y, type);

				if (!checkTileExists(x, y, zoom))
					return null;

				try {
					URL u = new URL(s);
					HttpURLConnection huc = (HttpURLConnection) u.openConnection();
					huc.setRequestMethod("HEAD");
					return huc.getResponseCode() == 200 ? u : null;
				} catch (Exception e) {
					throw new AssertionError(e);
				}
			}

			@SuppressWarnings("BooleanMethodIsAlwaysInverted")
			private boolean checkTileExists(int x, int y, int zoom) {
				int minZoom = 10;
				int maxZoom = MAX_ZOOM;

				return !(zoom < minZoom || zoom > maxZoom);
			}
		};

		map.setMaxZoomPreference(MAX_ZOOM);

		if (checkLocationPermission(getContext(), false)) {
			locationListener.followMyPosition = true;
			if (locationManager == null)
				locationManager = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
			Location l = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
			if (l != null) {
				CameraPosition cp = CameraPosition.builder().target(new LatLng(l.getLatitude(), l.getLongitude())).zoom(16).build();
				map.moveCamera(CameraUpdateFactory.newCameraPosition(cp));
				locationListener.position = cp.target;
				DrawUserPosition(cp.target, l.getAccuracy());
			}
		}

		map.setOnCameraMoveStartedListener(locationListener.cameraChangeListener);
		activeOverlay = map.addTileOverlay(new TileOverlayOptions().tileProvider(tileProvider));
		if (type != null)
			changeMapOverlay(type);
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
					.fillColor(ContextCompat.getColor(c, R.color.colorUserAccuracy))
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

	private class UpdateLocationListener implements LocationListener {
		LatLng position;
		boolean followMyPosition = false;

		private final GoogleMap.OnCameraMoveStartedListener cameraChangeListener = i -> {
			if (followMyPosition && i == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE)
				followMyPosition = false;
		};

		@Override
		public void onLocationChanged(Location location) {
			position = new LatLng(location.getLatitude(), location.getLongitude());
			DrawUserPosition(position, location.getAccuracy());
			if (followMyPosition && map != null)
				moveTo(position);
		}

		private void moveToMyPosition() {
			followMyPosition = true;
			if (position != null)
				moveTo(position);
		}

		private void moveTo(@NonNull LatLng latlng) {
			float zoom = map.getCameraPosition().zoom;
			moveTo(latlng, zoom < 16 ? 16 : zoom > 17 ? 17 : zoom);
		}

		private void moveTo(@NonNull LatLng latlng, float zoom) {
			CameraPosition cPos = map.getCameraPosition();
			if (cPos.target.latitude != latlng.latitude || cPos.target.longitude != latlng.longitude)
				map.animateCamera(CameraUpdateFactory.newLatLngZoom(latlng, zoom));
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
	}

}
