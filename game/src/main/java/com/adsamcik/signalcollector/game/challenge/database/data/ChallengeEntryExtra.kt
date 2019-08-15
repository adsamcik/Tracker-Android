package com.adsamcik.signalcollector.game.challenge.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(foreignKeys = [ForeignKey(entity = ChallengeEntry::class, parentColumns = ["id"], childColumns = ["entry_id"],
		onDelete = ForeignKey.CASCADE)])
abstract class ChallengeEntryExtra(
		@ColumnInfo(name = "entry_id")
		val entryId: Long,
		@ColumnInfo(name = "completed")
		var isCompleted: Boolean
) {
	@PrimaryKey(autoGenerate = true)
	var id: Long = 0
}
