package com.adsamcik.signalcollector.data;

import android.content.res.Resources;

import com.adsamcik.signalcollector.R;

public class UploadStats {
	public final long time;
	public final int newWifi, newCell, cell, wifi, collections, noiseCollections, newLocations, newNoiseLocations;
	public final long uploadSize;

	public UploadStats(long time, int wifi, int newWifi, int cell, int newCell, int collections, int newLocations, int noiseCollections, long uploadSize, int newNoiseLocations) {
		this.time = time;
		this.newCell = newCell;
		this.newWifi = newWifi;
		this.cell = cell;
		this.wifi = wifi;
		this.collections = collections;
		this.noiseCollections = noiseCollections;
		this.uploadSize = uploadSize;
		this.newLocations = newLocations;
		this.newNoiseLocations = newNoiseLocations;
	}

	public String generateNotificationText(Resources resources) {
		StringBuilder stringBuilder = new StringBuilder();
		int newLocations = this.newLocations + this.newNoiseLocations;
		stringBuilder.append(resources.getString(R.string.notification_found)).append(' ');
		stringBuilder.append(resources.getQuantityString(R.plurals.new_locations, newLocations)).append(", ");

		if (newWifi > 0)
			stringBuilder.append(resources.getString(R.string.new_wifi, newWifi)).append(", ");
		if (newCell > 0)
			stringBuilder.append(resources.getString(R.string.new_cell, newCell)).append(", ");

		stringBuilder.setLength(stringBuilder.length() - 2);
		return stringBuilder.toString();

	}
}
