package com.adsamcik.signalcollector.classes;

public class UploadStats {
	public final int newWifi, newCell, cell, wifi, locations, noiseCollections;

	public UploadStats(int newWifi, int newCell, int cell, int wifi, int locations, int noiseCollections) {
		this.newCell = newCell;
		this.newWifi = newWifi;
		this.cell = cell;
		this.wifi = wifi;
		this.locations = locations;
		this.noiseCollections = noiseCollections;
	}
}
