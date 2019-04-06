package com.adsamcik.signalcollector.game.challenge.data.progress

import androidx.room.ColumnInfo

class ExplorerChallengeProgressData(
		@ColumnInfo(name = "completed")
		override var isCompleted: Boolean = false,
		@ColumnInfo(name = "location_count")
		var locationCount: Int = 0) : ChallengeProgressData