package com.adsamcik.signalcollector.data;

import com.vimeo.stag.UseStag;

@UseStag
public class StatData {
	//todo add final if stag supports it in the future
	public String id;
	public String value;

	StatData(String id, String value) {
		this.id = id;
		this.value = value;
	}

	//STAG CONSTRUCTOR//
	StatData() {}
}
