package com.adsamcik.signalcollector.data;

public class StatDay {
	private int age;
	private int wifi;
	private int cell;
	private int locations;
	private int minutes;
	private long upload;

	public StatDay() {
	}

	public StatDay(int minutes, int locations, int wifi, int cell, int age, long upload) {
		this.minutes = minutes;
		this.wifi = wifi;
		this.cell = cell;
		this.locations = locations;
		this.age = age;
		this.upload = upload;
	}

	public void add(int cellCount, int wifiCount) {
		bumpLocation().addCell(cellCount).addWifi(wifiCount);
	}

	public void add(StatDay day) {
		locations += day.locations;
		wifi += day.wifi;
		cell += day.cell;
		minutes += day.minutes;
	}

	public StatDay addCell(int value) {
		cell += value;
		return this;
	}

	public StatDay addWifi(int value) {
		wifi += value;
		return this;
	}

	public StatDay addMinutes(int value) {
		minutes += value;
		return this;
	}

	public StatDay bumpLocation() {
		locations++;
		return this;
	}

	public int age(int value) {
		return age += value;
	}

	public int getWifi() {
		return wifi;
	}

	public int getCell() {
		return cell;
	}

	public int getLocations() {
		return locations;
	}

	public int getMinutes() {
		return minutes;
	}

	public long getUploaded() {
		return upload;
	}
}
