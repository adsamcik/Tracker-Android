package com.adsamcik.signalcollector.game.challenge.database.data.extra

import androidx.room.ColumnInfo
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntryExtra

class WalkInTheParkChallengeEntry(entryId: Long,
                                  isCompleted: Boolean,
                                  @ColumnInfo(name = "required_distance")
                                  val requiredDistanceInM: Float,
                                  @ColumnInfo(name = "distance")
                                  var distanceInM: Float) : ChallengeEntryExtra(entryId, isCompleted) {

}