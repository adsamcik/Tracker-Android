package com.adsamcik.tracker.tracker.component.producer

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.data.CellInfo
import com.adsamcik.tracker.shared.base.data.CellType
import com.adsamcik.tracker.shared.base.data.NetworkOperator
import com.adsamcik.tracker.shared.base.extension.getSystemServiceTyped
import com.adsamcik.tracker.shared.base.extension.hasCellScanPermission
import com.adsamcik.tracker.shared.base.extension.hasReadPhonePermission
import com.adsamcik.tracker.shared.base.extension.telephonyManager
import com.adsamcik.tracker.shared.preferences.PreferenceKeys
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.tracker.component.TrackerDataProducerComponent
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.CellScanData
import android.os.SystemClock
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder
import com.adsamcik.tracker.tracker.utility.TelephonyUtils
import kotlinx.coroutines.flow.map
import java.util.ArrayList

internal class CellDataProducer(
	changeReceiver: TrackerDataProducerObserver,
	trackingParamsRepository: TrackingParamsRepository? = null,
) : TrackerDataProducerComponent(
	changeReceiver,
	enabledFlow = trackingParamsRepository?.data?.map { it.cellEnabled },
) {
	override val preferenceKey: String = PreferenceKeys.CELL_ENABLED
	override val preferenceDefault: Boolean = PreferenceKeys.CELL_ENABLED_DEFAULT

	private var telephonyManager: TelephonyManager? = null
	private var subscriptionManager: SubscriptionManager? = null

	private var context: Context? = null

	// Simple TTL cache to avoid expensive telephonyManager.allCellInfo calls every collection tick.
	private var lastCellScanData: CellScanData? = null
	private var lastCellScanElapsedRealtimeMillis: Long = -1L
	private var lastPersistedFingerprint: String? = null
	private var lastPersistedElapsedRealtimeMillis: Long = -1L

	override fun onDataRequest(builder: TrackingCycleBuilder) {
		val context = requireNotNull(context)
		if (!context.hasCellScanPermission) {
			lastCellScanData = null
			lastCellScanElapsedRealtimeMillis = -1L
			return
		}

		// If airplane mode is enabled do not provide stale data.
		if (Assist.isAirplaneModeEnabled(context)) {
			lastCellScanData = null
			return
		}

		val now = SystemClock.elapsedRealtime()
		val telephonyManager = requireNotNull(telephonyManager)
		val needsRefresh = lastCellScanData == null || now - lastCellScanElapsedRealtimeMillis > CELL_SCAN_CACHE_TTL_MS

		if (needsRefresh) {
			val scanData = if (context.hasReadPhonePermission) {
				val subscriptionManager = requireNotNull(subscriptionManager)
				// Requires suppress missing permission because lint does not properly work with context.hasReadPhonePermission
				@Suppress("MissingPermission")
				getScanData(telephonyManager, subscriptionManager)
			} else {
				getScanData(telephonyManager)
			}

			if (scanData != null) {
				lastCellScanData = scanData
				lastCellScanElapsedRealtimeMillis = now
				val fingerprint = CellSnapshotGate.fingerprint(
					scanData.registeredCells.map { cell ->
						CellFingerprintReading(
							cellId = cell.cellId,
							areaCode = cell.areaCode,
							mcc = cell.networkOperator.mcc,
							mnc = cell.networkOperator.mnc,
							networkType = cell.type.ordinal,
							signalStrengthAsu = cell.asu,
						)
					}
				)
				if (CellSnapshotGate.shouldRecordSnapshot(
						fingerprint, lastPersistedFingerprint, now,
						lastPersistedElapsedRealtimeMillis, CELL_SNAPSHOT_HEARTBEAT_MS,
					)) {
					builder.cellScanFresh = true
					lastPersistedFingerprint = fingerprint
					lastPersistedElapsedRealtimeMillis = now
				}
			}
		}

		lastCellScanData?.let { builder.cellScan = it }
	}

	@Suppress("MagicNumber")
	private fun getScanData(telephonyManager: TelephonyManager): CellScanData? {
		val networkOperator = telephonyManager.networkOperator
		return if (networkOperator.isNotEmpty()) {
			val mcc = networkOperator.substring(0, 3)
			val mnc = networkOperator.substring(3)

			val registeredOperator = NetworkOperator(mcc, mnc, telephonyManager.networkOperatorName)

			getScanData(telephonyManager, listOf(registeredOperator))
		} else {
			null
		}
	}

	@RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
	private fun getScanData(
			telephonyManager: TelephonyManager,
			subscriptionManager: SubscriptionManager
	): CellScanData? {
		val list = mutableListOf<NetworkOperator>()
		val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList
		if (activeSubscriptions == null) {
			return getScanData(telephonyManager)
		} else {
			activeSubscriptions.forEach {
				val mcc: String?
				val mnc: String?

				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					mcc = it.mccString
					mnc = it.mncString
				} else {
					@Suppress("deprecation")
					mcc = it.mcc.toString()
					@Suppress("deprecation")
					mnc = it.mnc.toString()
				}

				if (mcc != null && mnc != null) {
					list.add(NetworkOperator(mcc, mnc, it.carrierName.toString()))
				}
			}

			return if (list.isNotEmpty()) {
				getScanData(telephonyManager, list)
			} else {
				null
			}
		}
	}

	private fun getScanData(
			telephonyManager: TelephonyManager,
			registeredOperators: List<NetworkOperator>
	): CellScanData? {
		@SuppressLint("MissingPermission")
		val cellInfo = telephonyManager.allCellInfo ?: return null

		val phoneCount = TelephonyUtils.getPhoneCount(telephonyManager)
		val registeredCells = ArrayList<CellInfo>(phoneCount)

		cellInfo.forEach {
			if (it.isRegistered) {
				convertToCellInfo(it, registeredOperators)?.let { cellInfo ->
					registeredCells.add(cellInfo)
				}

				if (registeredCells.size == phoneCount - 1) return@forEach
			}
		}

		if (registeredCells.isEmpty()) {
			registeredOperators.forEach {
				registeredCells.add(
						CellInfo(
								it,
								cellId = 0,
								type = CellType.None,
								asu = 0,
								dbm = 0,
								level = 0
						)
				)
			}
		}

		return CellScanData(registeredOperators, cellInfo, registeredCells)
	}
	@Suppress("ComplexMethod", "DEPRECATION")
	private fun convertToCellInfo(
			cellInfo: android.telephony.CellInfo,
			registeredOperator: List<NetworkOperator>
	): CellInfo? {
		return when {
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is CellInfoNr -> {
				registeredOperator.find { it.sameNetwork(cellInfo) }?.let {
					CellInfo(
						cellInfo.cellIdentity as CellIdentityNr,
						cellInfo.cellSignalStrength as CellSignalStrengthNr,
						it
					)
				}
			}
			cellInfo is CellInfoLte -> {
				registeredOperator.find { it.sameNetwork(cellInfo) }?.let {
					CellInfo(cellInfo.cellIdentity, cellInfo.cellSignalStrength, it)
				}
			}
			cellInfo is CellInfoGsm -> {
				registeredOperator.find { it.sameNetwork(cellInfo) }?.let {
					CellInfo(cellInfo.cellIdentity, cellInfo.cellSignalStrength, it)
				}
			}
			cellInfo is CellInfoWcdma -> {
				registeredOperator.find { it.sameNetwork(cellInfo) }?.let {
					CellInfo(cellInfo.cellIdentity, cellInfo.cellSignalStrength, it)
				}
			}
			cellInfo is android.telephony.CellInfoCdma -> {
				@Suppress("DEPRECATION")
				registeredOperator.find { it.sameNetwork(cellInfo) }?.let {
					CellInfo.fromCdma(cellInfo.cellIdentity, cellInfo.cellSignalStrength, it)
				}
			}
			else -> {
				Reporter.report(Throwable("Unknown cell type ${cellInfo.javaClass.simpleName}"))
				null
			}
		}
	}

	override suspend fun onEnable(context: Context) {
		this.context = context
		telephonyManager = context.telephonyManager
		subscriptionManager = context.getSystemServiceTyped(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
		super.onEnable(context)
	}

	override suspend fun onDisable(context: Context) {
		this.context = null
		telephonyManager = null
		subscriptionManager = null
		lastCellScanData = null
		lastPersistedFingerprint = null
		lastPersistedElapsedRealtimeMillis = -1L
		lastCellScanElapsedRealtimeMillis = -1L
		super.onDisable(context)
	}

	companion object {
		// Chosen to balance freshness vs radio / framework overhead. Adjust after profiling if needed.
		private const val CELL_SCAN_CACHE_TTL_MS = 60_000L
		private const val CELL_SNAPSHOT_HEARTBEAT_MS = 5 * 60_000L
	}
}
