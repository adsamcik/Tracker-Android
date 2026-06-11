package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import com.adsamcik.tracker.shared.base.database.data.GenericPreference

/**
 * Dao for generic preferences.
 * This can replace Shared Preferences.
 */
@Dao
interface GenericPreferenceDao : BaseDao<GenericPreference>
