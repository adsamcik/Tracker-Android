package com.adsamcik.signalcollector.game.challenge.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntryExtra

@Entity(tableName = "challenge_explorer", inheritSuperIndices = true)
class ExplorerChallengeEntity(entryId: Long,
                              isCompleted: Boolean,
                              @ColumnInfo(name = "required_location_count")
                              val requiredLocationCount: Int,
                              @ColumnInfo(name = "location_count")
                              var locationCount: Int
) : ChallengeEntryExtra(entryId, isCompleted)

