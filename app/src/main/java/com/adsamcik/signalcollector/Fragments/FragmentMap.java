package com.adsamcik.signalcollector.Fragments;


import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.DragEvent;
import android.view.InflateException;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import com.adsamcik.signalcollector.Network;
import com.adsamcik.signalcollector.R;
import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.TileOverlayOptions;
import com.google.android.gms.maps.model.TileProvider;
import com.google.android.gms.maps.model.UrlTileProvider;

import java.net.MalformedURLException;
import java.net.URL;

public class FragmentMap extends Fragment implements OnMapReadyCallback {
	public static View view;
	public SupportMapFragment mMapFragment;
	public GoogleMap map;
	public String type = "Wifi";
	public TileProvider tileProvider;
	public ImageButton switchButton;
	public UpdateInfoReceiver updateReceiver;
	public boolean permissions = false;
	boolean isActive = false;


	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		if(view != null) {
			ViewGroup parent = (ViewGroup) view.getParent();
			if(parent != null)
				parent.removeView(view);
		}
		try {
			view = inflater.inflate(R.layout.fragment_map, container, false);
		} catch(InflateException e) {
		/* map is already there, just return view as it is */
		}

		mMapFragment = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.map);
		mMapFragment.getMapAsync(this);

		//noinspection BooleanMethodIsAlwaysInverted,BooleanMethodIsAlwaysInverted,BooleanMethodIsAlwaysInverted,BooleanMethodIsAlwaysInverted,BooleanMethodIsAlwaysInverted,BooleanMethodIsAlwaysInverted,BooleanMethodIsAlwaysInverted
		tileProvider = new UrlTileProvider(256, 256) {
			@Override
			public URL getTileUrl(int x, int y, int zoom) {

    /* Define the URL pattern for the tile images */
				String s = String.format(Network.URL_TILES + "z%dx%dy%dt%s.png", zoom, x, y, type);

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


		switchButton = (ImageButton) view.findViewById(R.id.btn_switch_type);
		switchButton.setImageDrawable(ContextCompat.getDrawable(getContext(), R.drawable.ic_network_cell_24dp));
		switchButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				ChangeMapOverlay(type.equals("Wifi") ? "Cell" : "Wifi");
			}
		});

		view.findViewById(R.id.map).setOnDragListener(new View.OnDragListener() {
			@Override
			public boolean onDrag(View v, DragEvent event) {
				Log.d("drag", "drag");
				return false;
			}
		});

		return view;
	}

	public void SetActive(boolean active) {
		isActive = active;
		if(map != null) {
			if(active)
				LocalBroadcastManager.getInstance(getContext()).registerReceiver(updateReceiver = new UpdateInfoReceiver(), new IntentFilter(UpdateInfoReceiver.BROADCAST_TAG));
			else {
				LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(updateReceiver);
				updateReceiver.Cleanup();
			}

			if(ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
				map.setMyLocationEnabled(active);
		}
	}

	private void ChangeMapOverlay(String type) {
		this.type = type;
		map.clear();
		map.addTileOverlay(new TileOverlayOptions().tileProvider(tileProvider));

		if(type.equals("Wifi"))
			switchButton.setImageDrawable(ContextCompat.getDrawable(getContext(), R.drawable.ic_network_cell_24dp));
		else
			switchButton.setImageDrawable(ContextCompat.getDrawable(getContext(), R.drawable.ic_network_wifi_24dp));

	}

	@Override
	public void onMapReady(GoogleMap map) {
		this.map = map;
		ChangeMapOverlay(type);
		if(isActive) {
			SetActive(true);
			updateReceiver = new UpdateInfoReceiver();
			LocalBroadcastManager.getInstance(getContext()).registerReceiver(updateReceiver, new IntentFilter(UpdateInfoReceiver.BROADCAST_TAG));
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
		if(requestCode == 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
			SetActive(this.permissions = true);
	}

	public class UpdateInfoReceiver extends BroadcastReceiver {
		public static final String BROADCAST_TAG = "signalCollectorUpdate";
		boolean updateMove = false;
		boolean updatePosition = false;

		public UpdateInfoReceiver() {
			map.setOnMyLocationButtonClickListener(new GoogleMap.OnMyLocationButtonClickListener() {
				@Override
				public boolean onMyLocationButtonClick() {
					updatePosition = true;
					updateMove = true;
					return false;
				}
			});

			map.setOnCameraChangeListener(new GoogleMap.OnCameraChangeListener() {
				@Override
				public void onCameraChange(CameraPosition cameraPosition) {
					if(!updateMove)
						updatePosition = false;
					updateMove = false;
				}
			});

		}

		public void Cleanup() {
			map.setOnCameraChangeListener(null);
			map.setOnMyLocationButtonClickListener(null);
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			if(updatePosition && map != null) {
				CameraUpdate center = CameraUpdateFactory.newLatLng(new LatLng(intent.getDoubleExtra("latitude", 0), intent.getDoubleExtra("longitude", 0)));
				//CameraUpdate zoom = CameraUpdateFactory.zoomTo(17);
				updateMove = true;
				map.moveCamera(center);
				//map.animateCamera(zoom);
			}
		}
	}
}
