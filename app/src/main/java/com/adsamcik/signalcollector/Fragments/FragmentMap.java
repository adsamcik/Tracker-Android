package com.adsamcik.signalcollector.fragments;


import android.Manifest;
import android.app.Activity;
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
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.adsamcik.signalcollector.classes.Network;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.classes.Success;
import com.adsamcik.signalcollector.interfaces.ITabFragment;
import com.adsamcik.signalcollector.play.PlayController;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
import com.google.android.gms.maps.model.TileProvider;
import com.google.android.gms.maps.model.UrlTileProvider;
import com.google.firebase.crash.FirebaseCrash;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class FragmentMap extends Fragment implements OnMapReadyCallback, ITabFragment {
	private static final String TAG = "SIGNALS MAP";
	private static final String[] availableTypes = {"Wifi", "Cell"};
	private static int typeIndex = -1;
	private SupportMapFragment mMapFragment;
	private GoogleMap map;
	private TileProvider tileProvider;

	private LocationManager locationManager;
	private final UpdateLocationListener locationListener = new UpdateLocationListener();

	private TileOverlay activeOverlay;

	private View view;

	private Circle userRadius;
	private Marker userCenter;


	/**
	 * Check if permission to access fine location is granted
	 * If not and is android 6 or newer, than it prompts you to enable it
	 *
	 * @return is permission available atm
	 */
	private boolean checkLocationPermission(boolean request) {
		if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
			return true;
		else if (request)
			requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 0);
		return false;
	}

	/**
	 * This function should be called when fragment is left
	 */
	public void onLeave() {
		if (checkLocationPermission(false)) {
			if (locationManager == null)
				FirebaseCrash.log("Location manager is null on leave");
			else
				locationManager.removeUpdates(locationListener);
		}
		locationListener.cleanup();
	}

	/**
	 * Initializes fabs for Map fragment
	 *
	 * @param fabOne fabOne (lower)
	 * @param fabTwo fabTwo (above fabOne)
	 */
	public Success onEnter(Activity activity, FloatingActionButton fabOne, FloatingActionButton fabTwo) {
		if (!PlayController.isPlayServiceAvailable(activity))
			return new Success("Play services are out of date or unavailable.");

		if (checkLocationPermission(true)) {
			if (locationManager == null)
				locationManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
			else
				locationManager.requestLocationUpdates(1, 5, new Criteria(), locationListener, Looper.myLooper());
		} else
			return new Success("App does not have required permissions.");

		fabOne.show();
		fabOne.setImageResource(R.drawable.ic_gps_fixed_black_24dp);
		fabOne.setOnClickListener(v -> {
			if (checkLocationPermission(true))
				locationListener.moveToMyPosition();
		});

		fabTwo.show();
		fabTwo.setImageResource(R.drawable.ic_network_cell_24dp);
		fabTwo.setOnClickListener(v -> changeMapOverlay(typeIndex + 1 == availableTypes.length ? 0 : typeIndex + 1, fabTwo));

		if (mMapFragment == null) {
			mMapFragment = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.map);
			mMapFragment.getMapAsync(this);

			tileProvider = new UrlTileProvider(256, 256) {
				@Override
				public URL getTileUrl(int x, int y, int zoom) {
					String s = String.format(Locale.ENGLISH, Network.URL_TILES, zoom, x, y, availableTypes[typeIndex]);

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
					int maxZoom = 17;

					return !(zoom < minZoom || zoom > maxZoom);
				}
			};
		}
		return new Success();
	}

	/**
	 * Change map overlay
	 *
	 * @param index new overlay string index
	 */
	private void changeMapOverlay(int index, @NonNull FloatingActionButton fab) {
		if (map == null) {
			FirebaseCrash.report(new Throwable("changeMapOverlay should not be called before map is initialized"));
			Log.e("Map", "changeMapOverlay should not be called before map is initialized");
			return;
		} else if (index < 0 || index >= availableTypes.length)
			throw new RuntimeException("Index is out of range");

		if (index != typeIndex || activeOverlay == null) {
			typeIndex = index;
			if (activeOverlay != null)
				activeOverlay.remove();
			activeOverlay = map.addTileOverlay(new TileOverlayOptions().tileProvider(tileProvider));
		}

		switch (availableTypes[typeIndex]) {
			case "Wifi":
				fab.setImageDrawable(ContextCompat.getDrawable(getContext(), R.drawable.ic_network_cell_24dp));
				break;
			case "Cell":
				fab.setImageDrawable(ContextCompat.getDrawable(getContext(), R.drawable.ic_network_wifi_24dp));
				break;
		}
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		view = inflater.inflate(R.layout.fragment_map, container, false);
		return super.onCreateView(inflater, container, savedInstanceState);
	}

	@Override
	public void onMapReady(GoogleMap map) {
		this.map = map;

		if (checkLocationPermission(false)) {
			locationListener.followMyPosition = true;
			if (locationManager == null)
				locationManager = (LocationManager) getActivity().getSystemService(Context.LOCATION_SERVICE);
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
		typeIndex = 0;
	}

	/**
	 * Draws user accuracy radius and location
	 * Is automatically initialized if no circle exists
	 *
	 * @param latlng   Latitude and longitude
	 * @param accuracy Accuracy
	 */
	private void DrawUserPosition(LatLng latlng, float accuracy) {
		if (userRadius == null) {
			Context c = getContext();
			if (c == null) {
				if (getActivity() != null)
					c = getActivity().getApplicationContext();
				else
					return;
			}
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

		private final GoogleMap.OnCameraMoveStartedListener cameraChangeListener = new GoogleMap.OnCameraMoveStartedListener() {
			@Override
			public void onCameraMoveStarted(int i) {
				if (followMyPosition && i == REASON_GESTURE)
					followMyPosition = false;

				if (map.getCameraPosition().zoom > 17)
					map.animateCamera(CameraUpdateFactory.zoomTo(17));
			}
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
