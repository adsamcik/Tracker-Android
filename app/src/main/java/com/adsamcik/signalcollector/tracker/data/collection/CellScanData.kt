package com.adsamcik.signalcollector.tracker.data.collection

import android.telephony.CellInfo

internal data class CellScanData(val registeredOperators: Collection<RegisteredOperator>,
                                 val cellScanData: Collection<CellInfo>,
                                 val registeredCells: Collection<com.adsamcik.signalcollector.common.data.CellInfo>)