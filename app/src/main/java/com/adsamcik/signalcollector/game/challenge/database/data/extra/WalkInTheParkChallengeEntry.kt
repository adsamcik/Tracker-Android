package com.adsamcik.signalcollector.game.challenge.database.data.extra

import androidx.room.ColumnInfo
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntryExtra

class WalkInTheParkChallengeEntry(entryId: Long,
                                  isCompleted: Boolean,
                                  @ColumnInfo(name = "required_distance")
                                  val requiredDistanceInM: Int,
                                  @ColumnInfo(name = "distance")
                                  var distanceInM: Int) : ChallengeEntryExtra(entryId, isCompleted) {

}