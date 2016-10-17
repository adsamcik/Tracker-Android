package com.adsamcik.signalcollector.data;

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
}
