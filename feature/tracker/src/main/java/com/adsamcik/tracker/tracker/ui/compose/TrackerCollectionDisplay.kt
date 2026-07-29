package com.adsamcik.tracker.tracker.ui.compose

import android.content.Context
import androidx.annotation.StringRes
import com.adsamcik.tracker.shared.base.R as BaseR
import com.adsamcik.tracker.tracker.data.collection.TrackerActivityGroup
import com.adsamcik.tracker.tracker.data.collection.TrackerActivitySnapshot
import com.adsamcik.tracker.tracker.data.collection.TrackerCellType

internal fun TrackerActivitySnapshot.getGroupedActivityName(context: Context): String =
    context.getString(group.stringRes)

@get:StringRes
private val TrackerActivityGroup.stringRes: Int
    get() = when (this) {
        TrackerActivityGroup.STILL -> BaseR.string.activity_still
        TrackerActivityGroup.ON_FOOT -> BaseR.string.activity_on_foot
        TrackerActivityGroup.IN_VEHICLE -> BaseR.string.activity_in_vehicle
        TrackerActivityGroup.UNKNOWN -> BaseR.string.activity_unknown
    }

@get:StringRes
internal val TrackerCellType.nameRes: Int
    get() = when (this) {
        TrackerCellType.UNKNOWN -> BaseR.string.cell_unknown
        TrackerCellType.GSM -> BaseR.string.cell_gsm
        TrackerCellType.CDMA -> BaseR.string.cell_cdma
        TrackerCellType.WCDMA -> BaseR.string.cell_wcdma
        TrackerCellType.LTE -> BaseR.string.cell_lte
        TrackerCellType.NR -> BaseR.string.cell_nr
        TrackerCellType.NONE -> BaseR.string.cell_none
    }
