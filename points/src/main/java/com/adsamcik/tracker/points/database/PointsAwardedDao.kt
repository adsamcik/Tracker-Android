package com.adsamcik.tracker.points.database

import androidx.room.Dao
import com.adsamcik.tracker.points.data.PointsAwarded
import com.adsamcik.tracker.shared.base.database.dao.BaseDao

/**
 * DAO for awarded points
 */
@Dao
interface PointsAwardedDao : BaseDao<PointsAwarded>
