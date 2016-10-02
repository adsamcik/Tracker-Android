package com.adsamcik.signalcollector.classes;

public class UploadStats {
	public final int newWifi, newCell, cell, wifi, collections, noiseCollections, newLocations;
	public final long uploadSize;

	public UploadStats(int wifi, int newWifi, int cell, int newCell, int collections, int newLocations, int noiseCollections, long uploadSize) {
		this.newCell = newCell;
		this.newWifi = newWifi;
		this.cell = cell;
		this.wifi = wifi;
		this.collections = collections;
		this.noiseCollections = noiseCollections;
		this.uploadSize = uploadSize;
		this.newLocations = newLocations;
	}
}
