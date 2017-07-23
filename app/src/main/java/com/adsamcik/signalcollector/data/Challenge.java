package com.adsamcik.signalcollector.data;

public class Challenge {
	public String title;
	public String description;
	public String[] descVars;
	public boolean isDone;

	public Challenge() {}

	public Challenge(String title, String description, boolean isDone) {
		this.title = title;
		this.description = description;
		this.isDone = isDone;
		this.descVars = null;
	}

	public Challenge(String title, String[] descVars, boolean isDone) {
		this.title = title;
		this.descVars = descVars;
		this.description = null;
		this.isDone = false;
	}
}
