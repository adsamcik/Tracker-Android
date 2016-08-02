package com.adsamcik.signalcollector.data;

public class StatDay {
	private int age;
	private int wifi;
	private int cell;
	private int locations;

	public StatDay() {}

	public StatDay(int locations, int wifi, int cell, int age) {
		this.wifi = wifi;
		this.cell = cell;
		this.locations = locations;
		this.age = age;
	}

	public void add(int cellCount, int wifiCount) {
		bumpLocation().addCell(cellCount).addWifi(wifiCount);
	}

	public void add(StatDay day) {
		locations += day.locations;
		wifi += day.wifi;
		cell += day.cell;
	}

	public StatDay addCell(int value) {
		cell += value;
		return this;
	}

	public StatDay addWifi(int value) {
		wifi += value;
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
}
