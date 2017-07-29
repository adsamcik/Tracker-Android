package com.adsamcik.signalcollector.enums;

public class AppendPosition {
	private final int position;
	private final AppendBehavior appendBehavior;

	public AppendPosition(int position) {
		this.position = position;
		this.appendBehavior = AppendBehavior.Any;
	}

	public AppendPosition(AppendBehavior behavior) {
		this.appendBehavior = behavior;
		this.position = 0;
	}

	public enum AppendBehavior {
		First,
		Last,
		Any
	}
}
