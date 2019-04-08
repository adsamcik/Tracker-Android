package com.adsamcik.signalcollector.game.challenge.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntryExtra

@Entity(tableName = "walk_distance", inheritSuperIndices = true)
class WalkDistanceChallengeEntity(entryId: Long,
                                  isCompleted: Boolean,
                                  @ColumnInfo(name = "required_distance")
                                  val requiredDistanceInM: Float,
                                  @ColumnInfo(name = "distance")
                                  var distanceInM: Float) : ChallengeEntryExtra(entryId, isCompleted)