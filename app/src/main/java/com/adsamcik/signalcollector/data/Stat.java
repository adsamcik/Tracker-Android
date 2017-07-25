package com.adsamcik.signalcollector.data;

import android.util.JsonReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Stat {
	public final String name;
	public final String type;
	public final boolean showPosition;
	public final List<StatData> data;


	public Stat(String name, String type, boolean showPosition, List<StatData> data) {
		this.name = name;
		this.type = type;
		this.showPosition = showPosition;
		this.data = data;
	}
}
