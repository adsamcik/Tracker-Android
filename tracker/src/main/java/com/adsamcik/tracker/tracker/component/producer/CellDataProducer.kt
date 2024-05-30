package com.adsamcik.tracker.tracker.component.producer

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfoCdma
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
import com.adsamcik.tracker.shared.base.extension.hasReadPhonePermission
import com.adsamcik.tracker.shared.base.extension.telephonyManager
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.TrackerDataProducerComponent
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.CellScanData
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData
import com.adsamcik.tracker.tracker.utility.TelephonyUtils
import java.util.*

internal class CellDataProducer(changeReceiver: TrackerDataProducerObserver) :
		TrackerDataProducerComponent(
				changeReceiver
		) {
	override val keyRes: Int = R.string.settings_cell_enabled_key
	override val defaultRes: Int = R.string.settings_cell_enabled_default

	private var telephonyManager: TelephonyManager? = null
	private var subscriptionManager: SubscriptionManager? = null

	private var context: Context? = null

	override fun onDataRequest(tempData: MutableCollectionTempData) {
		val context = requireNotNull(context)
		if (!Assist.isAirplaneModeEnabled(context)) {
			val telephonyManager = requireNotNull(telephonyManager)

			val scanData = if (context.hasReadPhonePermission) {
				val subscriptionManager = requireNotNull(subscriptionManager)

				//Requires suppress missing permission because lint does not properly work with context.hasReadPhonePermission
				@Suppress("MissingPermission")
				getScanData(telephonyManager, subscriptionManager)
			} else {
				getScanData(telephonyManager)
			}

			if (scanData != null) {
				tempData.setCellData(scanData)
			}
		}
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

	@Suppress("ComplexMethod")
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
			cellInfo is CellInfoCdma -> {
				registeredOperator.find { it.sameNetwork(cellInfo) }?.let {
					CellInfo(cellInfo.cellIdentity, cellInfo.cellSignalStrength, it)
				}
			}
			else -> {
				Reporter.report(Throwable("Unknown cell type ${cellInfo.javaClass.simpleName}"))
				null
			}
		}
	}

	override fun onEnable(context: Context) {
		super.onEnable(context)
		this.context = context
		telephonyManager = context.telephonyManager

		subscriptionManager = context.getSystemServiceTyped(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
	}

	override fun onDisable(context: Context) {
		super.onDisable(context)
		this.context = null
		telephonyManager = null
		subscriptionManager = null
	}
}

