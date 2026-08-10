package com.adsamcik.tracker.tracker.source.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.CellIdentityCdma
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityTdscdma
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

internal data class CellBackendSnapshot(
	val subscriptionId: Int?,
	val observations: List<CellBackendObservation>,
)

internal data class CellBackendObservation(
	val identity: String,
	val radioType: String,
	val registered: Boolean,
	val signalLevelDbm: Int?,
	val providerTimestampNanos: Long?,
)

internal enum class CellRefreshRequestOutcome { REQUESTED, NOT_SUPPORTED, PERMISSION_BLOCKED, PROVIDER_FAILED }

@Singleton
internal class AndroidCellSourceBackend @Inject constructor(
	@ApplicationContext context: Context,
) {
	private val context = context.applicationContext
	private val baseManager = context.getSystemService(TelephonyManager::class.java)
	private val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
	private val executor: Executor = ContextCompat.getMainExecutor(context)
	private val registrations = linkedMapOf<Int?, CellRegistration>()

	@SuppressLint("MissingPermission")
	fun start(requestedSubscriptionIds: Set<Int>, callback: (CellBackendSnapshot) -> Unit): Boolean {
		if (baseManager == null) return false
		stop()
		return runCatching {
			resolveSubscriptionIds(requestedSubscriptionIds).forEach { subscriptionId ->
				val manager = managerFor(subscriptionId)
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
					val listener = Api31CellInfoCallback(subscriptionId, callback)
					manager.registerTelephonyCallback(executor, listener)
					registrations[subscriptionId] = CellRegistration(manager, listener, null)
				} else {
					@Suppress("DEPRECATION")
					val listener = LegacyCellInfoListener(subscriptionId, callback)
					@Suppress("DEPRECATION")
					manager.listen(listener, PhoneStateListener.LISTEN_CELL_INFO)
					registrations[subscriptionId] = CellRegistration(manager, null, listener)
				}
			}
			registrations.isNotEmpty()
		}.getOrElse { stop(); false }
	}

	fun stop(): Boolean {
		var success = true
		registrations.values.forEach { registration ->
			val removed = runCatching {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && registration.callback != null) {
					registration.manager.unregisterTelephonyCallback(registration.callback)
				} else if (registration.legacyListener != null) {
					@Suppress("DEPRECATION")
					registration.manager.listen(registration.legacyListener, PhoneStateListener.LISTEN_NONE)
				}
			}.isSuccess
			success = success && removed
		}
		registrations.clear()
		return success
	}

	@SuppressLint("MissingPermission")
	fun readCached(): List<CellBackendSnapshot> = registrations.mapNotNull { (subscriptionId, registration) ->
		runCatching {
			registration.manager.allCellInfo?.toBackendSnapshot(subscriptionId)
		}.getOrNull()
	}

	@SuppressLint("MissingPermission")
	fun requestRefresh(callback: (CellBackendSnapshot) -> Unit): CellRefreshRequestOutcome {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return CellRefreshRequestOutcome.NOT_SUPPORTED
		return try {
			registrations.forEach { (subscriptionId, registration) ->
				registration.manager.requestCellInfoUpdate(
					executor,
					object : TelephonyManager.CellInfoCallback() {
						override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
							callback(cellInfo.toBackendSnapshot(subscriptionId))
						}
					},
				)
			}
			CellRefreshRequestOutcome.REQUESTED
		} catch (_: SecurityException) {
			CellRefreshRequestOutcome.PERMISSION_BLOCKED
		} catch (_: RuntimeException) {
			CellRefreshRequestOutcome.PROVIDER_FAILED
		}
	}

	@SuppressLint("MissingPermission")
	private fun resolveSubscriptionIds(requested: Set<Int>): Set<Int?> {
		val active = runCatching {
			subscriptionManager?.activeSubscriptionInfoList
				?.mapTo(linkedSetOf()) { it.subscriptionId }
		}.getOrNull().orEmpty()
		return resolveCellSubscriptionScope(requested, active)
	}

	private fun managerFor(subscriptionId: Int?): TelephonyManager =
		if (subscriptionId == null) requireNotNull(baseManager)
		else requireNotNull(baseManager).createForSubscriptionId(subscriptionId)

	private data class CellRegistration(
		val manager: TelephonyManager,
		val callback: TelephonyCallback?,
		val legacyListener: PhoneStateListener?,
	)
}

@RequiresApi(Build.VERSION_CODES.S)
private class Api31CellInfoCallback(
	private val subscriptionId: Int?,
	private val callback: (CellBackendSnapshot) -> Unit,
) : TelephonyCallback(), TelephonyCallback.CellInfoListener {
	override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
		callback(cellInfo.toBackendSnapshot(subscriptionId))
	}
}

@Suppress("DEPRECATION")
private class LegacyCellInfoListener(
	private val subscriptionId: Int?,
	private val callback: (CellBackendSnapshot) -> Unit,
) : PhoneStateListener() {
	override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
		cellInfo?.let { callback(it.toBackendSnapshot(subscriptionId)) }
	}
}

private fun List<CellInfo>.toBackendSnapshot(subscriptionId: Int?) = CellBackendSnapshot(
	subscriptionId = subscriptionId,
	observations = mapNotNull(CellInfo::toBackendObservation)
		.sortedWith(compareBy(CellBackendObservation::radioType, CellBackendObservation::identity)),
)

@Suppress("DEPRECATION")
private fun CellInfo.toBackendObservation(): CellBackendObservation? {
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && this is CellInfoTdscdma) {
		val identity = cellIdentity
		return backendObservation(
			identity = "${identity.mccString}:${identity.mncString}:${identity.lac}:" +
				"${identity.cid}:${identity.cpid}",
			radioType = "TDSCDMA",
			signalLevelDbm = cellSignalStrength.dbm,
		)
	}
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && this is CellInfoNr) {
		val identity = cellIdentity as CellIdentityNr
		return backendObservation(
			identity = "${identity.mccString}:${identity.mncString}:${identity.tac}:" +
				"${identity.nci}:${identity.pci}",
			radioType = "NR",
			signalLevelDbm = cellSignalStrength.dbm,
		)
	}
	val identityAndType = when (this) {
		is CellInfoGsm -> (cellIdentity as CellIdentityGsm).let {
			"${it.operatorToken()}:${it.lac}:${it.cid}" to "GSM"
		}
		is CellInfoWcdma -> (cellIdentity as CellIdentityWcdma).let {
			"${it.operatorToken()}:${it.lac}:${it.cid}:${it.psc}" to "WCDMA"
		}
		is CellInfoLte -> (cellIdentity as CellIdentityLte).let {
			"${it.operatorToken()}:${it.tac}:${it.ci}:${it.pci}" to "LTE"
		}
		is CellInfoCdma -> (cellIdentity as CellIdentityCdma).let {
			"${it.systemId}:${it.networkId}:${it.basestationId}" to "CDMA"
		}
		else -> return null
	}
	val signal = when (this) {
		is CellInfoGsm -> cellSignalStrength.dbm
		is CellInfoWcdma -> cellSignalStrength.dbm
		is CellInfoLte -> cellSignalStrength.dbm
		is CellInfoCdma -> cellSignalStrength.dbm
		else -> null
	}
	return backendObservation(
		identity = identityAndType.first,
		radioType = identityAndType.second,
		signalLevelDbm = signal,
	)
}

@Suppress("DEPRECATION")
private fun CellInfo.backendObservation(
	identity: String,
	radioType: String,
	signalLevelDbm: Int?,
): CellBackendObservation {
	val timestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
		timestampMillis.takeIf { it > 0L }?.times(NANOS_PER_MILLISECOND)
	} else {
		timeStamp.takeIf { it > 0L }
	}
	return CellBackendObservation(identity, radioType, isRegistered, signalLevelDbm, timestamp)
}

@Suppress("DEPRECATION")
private fun CellIdentityGsm.operatorToken(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
	"$mccString:$mncString"
} else {
	"$mcc:$mnc"
}

@Suppress("DEPRECATION")
private fun CellIdentityWcdma.operatorToken(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
	"$mccString:$mncString"
} else {
	"$mcc:$mnc"
}

@Suppress("DEPRECATION")
private fun CellIdentityLte.operatorToken(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
	"$mccString:$mncString"
} else {
	"$mcc:$mnc"
}

private const val NANOS_PER_MILLISECOND = 1_000_000L
