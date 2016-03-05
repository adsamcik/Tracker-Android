package com.adsamcik.signalcollector.Fragments;


import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.InflateException;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.adsamcik.signalcollector.Network;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.TouchWrapper;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
import com.google.android.gms.maps.model.TileProvider;
import com.google.android.gms.maps.model.UrlTileProvider;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

public class FragmentMap extends Fragment implements OnMapReadyCallback {
	public static final String[] availableTypes = {"Wifi", "Cell"};
	public static int typeIndex = -1;
	public static View view;
	public SupportMapFragment mMapFragment;
	public GoogleMap map;
	public TileProvider tileProvider;
	public FloatingActionButton fabTwo, fabOne;
	public boolean permissions = false;
	boolean isActive = false;

	LocationManager locationManager;
	UpdateLocationListener locationListener;

	TouchWrapper touchWrapper;


	boolean checkLocationPermission() {
		if(ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
			return true;
		} else {
			requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 0);
			return false;
		}
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		if(view != null) {
			ViewGroup parent = (ViewGroup) view.getParent();
			if(parent != null)
				parent.removeView(view);
		}

		try {
			view = inflater.inflate(R.layout.fragment_map, container, false);
			touchWrapper = (TouchWrapper) view.findViewById(R.id.mapsLayout);
		} catch(InflateException e) {
		/* map is already there, just return view as it is */
		}

		mMapFragment = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.map);
		mMapFragment.getMapAsync(this);

		locationListener = new UpdateLocationListener();
		locationManager = (LocationManager) getActivity().getSystemService(Context.LOCATION_SERVICE);

		tileProvider = new UrlTileProvider(256, 256) {
			@Override
			public URL getTileUrl(int x, int y, int zoom) {

				String s = String.format(Locale.ENGLISH, Network.URL_TILES + "z%dx%dy%dt%s.png", zoom, x, y, availableTypes[typeIndex]);

				if(!checkTileExists(x, y, zoom)) {
					return null;
				}

				try {
					return new URL(s);
				} catch(MalformedURLException e) {
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

		return view;
	}

	public void onLeave() {
		isActive = false;
		if(checkLocationPermission())
			locationManager.removeUpdates(locationListener);
		locationListener.cleanup();
	}

	public void initializeFABs(FloatingActionButton fabOne, FloatingActionButton fabTwo) {
		isActive = true;
		this.fabOne = fabOne;
		this.fabTwo = fabTwo;

		if(checkLocationPermission())
			locationManager.requestLocationUpdates(1, 5, new Criteria(), locationListener, Looper.myLooper());

		fabOne.show();
		fabOne.setImageResource(R.drawable.ic_gps_fixed_black_24dp);
		fabOne.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if(checkLocationPermission()) {
					locationListener.moveToMyPosition();
				}
			}
		});

		fabTwo.show();
		fabTwo.setImageDrawable(ContextCompat.getDrawable(getContext(), R.drawable.ic_network_cell_24dp));
		fabTwo.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				changeMapOverlay(typeIndex + 1 == availableTypes.length ? 0 : typeIndex + 1);
			}
		});

		changeMapOverlay(typeIndex);
	}

	TileOverlay activeOverlay;

	private void changeMapOverlay(int index) {
		if(map == null) {
			Log.e("Map", "changeMapOverlay should not be called before map is initialized");
			return;
		} else if(index < 0 || index >= availableTypes.length)
			throw new RuntimeException("Index is out of range");

		if(index != typeIndex || activeOverlay == null) {
			typeIndex = index;
			if(activeOverlay != null)
				activeOverlay.remove();
			activeOverlay = map.addTileOverlay(new TileOverlayOptions().tileProvider(tileProvider));
		}

		if(fabTwo != null) {
			switch(availableTypes[typeIndex]) {
				case "Wifi":
					fabTwo.setImageDrawable(ContextCompat.getDrawable(getContext(), R.drawable.ic_network_cell_24dp));
					break;
				case "Cell":
					fabTwo.setImageDrawable(ContextCompat.getDrawable(getContext(), R.drawable.ic_network_wifi_24dp));
					break;
			}
		}
	}

	@Override
	public void onMapReady(GoogleMap map) {
		this.map = map;

		if(checkLocationPermission()) {
			locationListener.followMyPosition = true;
			Location l = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
			CameraPosition cp = CameraPosition.builder().target(new LatLng(l.getLatitude(), l.getLongitude())).zoom(16).build();
			map.moveCamera(CameraUpdateFactory.newCameraPosition(cp));
			locationListener.position = cp.target;
			DrawUserPosition(cp.target, l.getAccuracy());
		}

		map.setOnCameraChangeListener(locationListener.cameraChangeListener);
		changeMapOverlay(0);
	}

	Circle userRadius, userCenter;

	void DrawUserPosition(LatLng latlng, float accuracy) {
		if(userRadius == null) {
			userRadius = map.addCircle(new CircleOptions()
					.fillColor(ContextCompat.getColor(getContext(), R.color.colorUserAccuracy))
					.center(latlng)
					.radius(accuracy)
					.zIndex(100)
					.strokeWidth(0));
			userCenter = map.addCircle(new CircleOptions()
					.fillColor(ContextCompat.getColor(getContext(), R.color.colorPrimary))
					.center(latlng)
					.radius(5)
					.zIndex(100)
					.strokeWidth(5)
					.strokeColor(Color.WHITE));
		} else {
			userRadius.setCenter(latlng);
			userRadius.setRadius(accuracy);
			userCenter.setCenter(latlng);
		}
	}

	public class UpdateLocationListener implements LocationListener {
		LatLng position;
		boolean followMyPosition = false;

		public GoogleMap.OnCameraChangeListener cameraChangeListener = new GoogleMap.OnCameraChangeListener() {
			@Override
			public void onCameraChange(CameraPosition cameraPosition) {
				if(followMyPosition && touchWrapper.mMapIsTouched)
					followMyPosition = false;

				if(map.getCameraPosition().zoom > 17)
					map.animateCamera(CameraUpdateFactory.zoomTo(17));
			}
		};

		@Override
		public void onLocationChanged(Location location) {
			position = new LatLng(location.getLatitude(), location.getLongitude());
			DrawUserPosition(position, location.getAccuracy());
			if(touchWrapper.mMapIsTouched)
				followMyPosition = false;
			else if(followMyPosition && map != null)
				moveTo(position);
		}

		public void moveToMyPosition() {
			followMyPosition = true;
			moveTo(position);
		}

		public void moveTo(@NonNull double latitude, @NonNull double longitude) {
			moveTo(new LatLng(latitude, longitude));
		}

		public void moveTo(@NonNull LatLng latlng) {
			float zoom = map.getCameraPosition().zoom;
			moveTo(latlng,
					zoom < 16 ? 16 :
							zoom > 17 ? 17 : zoom);
		}

		public void moveTo(@NonNull LatLng latlng, @NonNull float zoom) {
			CameraPosition cPos = map.getCameraPosition();
			if(map != null && (cPos.target.latitude != latlng.latitude || cPos.target.longitude != latlng.longitude))
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

		public void cleanup() {
			map.setOnCameraChangeListener(null);
			map.setOnMyLocationButtonClickListener(null);
		}
	}

}
