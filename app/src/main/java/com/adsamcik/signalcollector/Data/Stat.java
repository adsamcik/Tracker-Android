package com.adsamcik.signalcollector.data;

import java.util.List;

public class Stat {
	public final String name;
	public final String type;
	public final boolean showPosition;
	public final List<StatData> statData;

	public Stat(String name, String type, boolean showPosition, List<StatData> statData) {
		this.name = name;
		this.type = type;
		this.showPosition = showPosition;
		this.statData = statData;
	}
}
