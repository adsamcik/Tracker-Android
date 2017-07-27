package com.adsamcik.signalcollector.data;

import com.vimeo.stag.UseStag;

@UseStag
public class StatData {
	public final String id;
	public final String value;

	StatData(String id, String value) {
		this.id = id;
		this.value = value;
	}
}
