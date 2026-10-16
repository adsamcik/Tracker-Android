package com.adsamcik.tracker.stats.data.repository

/** A saved open end may settle once; an already finite saved end must remain exact. */
internal fun Long.settlesRecordedRadioEnd(recordedEnd: Long): Boolean =
	this == recordedEnd || recordedEnd == Long.MAX_VALUE && this < Long.MAX_VALUE
