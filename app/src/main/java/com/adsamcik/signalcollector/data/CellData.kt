package com.adsamcik.signalcollector.data

import android.os.Build
import android.telephony.*
import androidx.annotation.RequiresApi
import com.squareup.moshi.JsonClass

/**
 * Data class that contains all the information about Cell.
 * It works universally with every supported cell technology
 * Supported technologies are GSM, CDMA, WCDMA and LTE
 */
@Suppress("DEPRECATION")
@JsonClass(generateAdapter = true)
data class CellData
/**
 * CellData constructor
 *
 * @param operatorName [CellData.operatorName]
 * @param type         [CellData.type]
 * @param id           [CellData.id]
 * @param mcc          [CellData.mcc]
 * @param mnc          [CellData.mnc]
 * @param dbm          [CellData.dbm]
 * @param asu          [CellData.asu]
 * @param level        [CellData.level]
 */(var operatorName: String,
    /**
     * Network type. Can have values: GSM {@value #GSM}, CDMA {@value #CDMA}, WCDMA {@value #WCDMA}, LTE {@value #LTE}
     */
    var type: Int,
    /**
     * Cell id
     * GSM - cid
     * CDMA - baseStationId
     * WCDMA - cid
     * LTE - ci
     */
    var id: Int,
    /**
     * Mobile country code
     * Replaced with System ID on CDMA
     */
    var mcc: String,
    /**
     * Mobile network code
     * Replaced with Network ID on CDMA
     */
    var mnc: String,
    /**
     * Strength of signal in decibels
     */
    var dbm: Int,
    /**
     * Strength of signal in asu
     */
    var asu: Int,
    /**
     * Signal strength as int 0...4 calculated by device
     */
    var level: Int) {

    /**
     * Converts int type to string
     *
     * @return type of network as string
     */
    fun getType(): String = when (type) {
        GSM -> "GSM"
        CDMA -> "CDMA"
        WCDMA -> "WCDMA"
        LTE -> "LTE"
        else -> "UNKNOWN"
    }

    companion object {
        const val GSM = 0
        const val CDMA = 1
        const val WCDMA = 2
        const val LTE = 3

        /**
         * Finds carrier name in subscriptions
         *
         * @param mnc                  Mobile network code
         * @param mcc                  Mobile country code
         * @param subscriptionInfoList Subscribed sim cards
         * @return carrier name or null if not found
         */
        @RequiresApi(22)
        private fun getCarrierName(mnc: String, mcc: String, subscriptionInfoList: List<SubscriptionInfo>): String? {
            if (mcc == Integer.MAX_VALUE.toString())
                return null

            return subscriptionInfoList
                    .firstOrNull { it.mcc.toString() == mcc && it.mnc.toString() == mnc }?.carrierName?.toString()
        }

        @RequiresApi(22)
        private fun getCarrierNameAndRemove(mnc: String, mcc: String, siList: MutableList<SubscriptionInfo>): String? {
            if (mcc == Integer.MAX_VALUE.toString())
                return null

            val iter = siList.iterator()
            while (iter.hasNext()) {
                val si = iter.next()
                if (si.mcc.toString() == mcc && si.mnc.toString() == mnc) {
                    val carrierName = si.carrierName.toString()
                    iter.remove()
                    return carrierName
                }
            }

            return null
        }

        /**
         * Creates new instance of CellData from GSM cell info
         *
         * @param cing         GSM cell info
         * @param operatorName network operator name
         * @return new CellData if successfull, null otherwise
         */
        fun newInstance(cing: CellInfoGsm, operatorName: String?): CellData? {
            if (operatorName == null)
                return null
            val cig = cing.cellIdentity
            val cssg = cing.cellSignalStrength

            val mcc: String
            val mnc: String
            if (Build.VERSION.SDK_INT == 28) {
                mcc = cig.mccString
                mnc = cig.mncString
            } else {
                mcc = cig.mcc.toString()
                mnc = cig.mnc.toString()
            }

            return CellData(operatorName, GSM, cig.cid, mcc, mnc, cssg.dbm, cssg.asuLevel, cssg.level)
        }


        @RequiresApi(22)
        fun newInstance(cing: CellInfoGsm, subscriptionInfoList: MutableList<SubscriptionInfo>): CellData? {
            val cig = cing.cellIdentity
            val mcc: String
            val mnc: String
            if (Build.VERSION.SDK_INT == 28) {
                mcc = cig.mccString
                mnc = cig.mncString
            } else {
                mcc = cig.mcc.toString()
                mnc = cig.mnc.toString()
            }
            return newInstance(cing, getCarrierNameAndRemove(mnc, mcc, subscriptionInfoList))
        }

        /**
         * Creates new instance of CellData from CDMA cell info
         *
         * @param cinc         CDMA cell info
         * @param operatorName network operator name
         * @return new CellData if successfull, null otherwise
         */
        fun newInstance(cinc: CellInfoCdma, operatorName: String?): CellData? {
            if (operatorName == null)
                return null
            val cic = cinc.cellIdentity
            val cssg = cinc.cellSignalStrength

            return CellData(operatorName, CDMA, cic.basestationId, cic.systemId.toString(), cic.networkId.toString(), cssg.dbm, cssg.asuLevel, cssg.level)
        }

        @RequiresApi(22)
        fun newInstance(cinc: CellInfoCdma, subscriptionInfoList: List<SubscriptionInfo>): CellData? {
            return if (subscriptionInfoList.size == 1)
                newInstance(cinc, subscriptionInfoList[0].carrierName.toString())
            else
                null
        }

        /**
         * Creates new instance of CellData from WCDMA cell info
         *
         * @param cinl         WCDMA cell info
         * @param operatorName network operator name
         * @return new CellData if successfull, null otherwise
         */
        fun newInstance(cinl: CellInfoWcdma, operatorName: String?): CellData? {
            if (operatorName == null)
                return null
            val cil = cinl.cellIdentity
            val cssg = cinl.cellSignalStrength

            val mcc: String
            val mnc: String
            if (Build.VERSION.SDK_INT == 28) {
                mcc = cil.mccString
                mnc = cil.mncString
            } else {
                mcc = cil.mcc.toString()
                mnc = cil.mnc.toString()
            }

            return CellData(operatorName, WCDMA, cil.cid, mcc, mnc, cssg.dbm, cssg.asuLevel, cssg.level)
        }


        @RequiresApi(22)
        fun newInstance(cinl: CellInfoWcdma, subscriptionInfoList: MutableList<SubscriptionInfo>): CellData? {
            return if (subscriptionInfoList.size == 1)
                newInstance(cinl, subscriptionInfoList[0].carrierName.toString())
            else {

                val mcc: String
                val mnc: String
                val cil = cinl.cellIdentity
                if (Build.VERSION.SDK_INT == 28) {
                    mcc = cil.mccString
                    mnc = cil.mncString
                } else {
                    mcc = cil.mcc.toString()
                    mnc = cil.mnc.toString()
                }

                newInstance(cinl, getCarrierNameAndRemove(mnc, mcc, subscriptionInfoList))
            }
        }


        /**
         * Creates new instance of CellData from LTE cell info
         *
         * @param cinl         LTE Cell Info
         * @param operatorName network operator name
         * @return new CellData if successfull, null otherwise
         */
        fun newInstance(cinl: CellInfoLte, operatorName: String?): CellData? {
            if (operatorName == null)
                return null
            val cil = cinl.cellIdentity
            val cssg = cinl.cellSignalStrength

            val mcc: String
            val mnc: String
            if (Build.VERSION.SDK_INT == 28) {
                mcc = cil.mccString
                mnc = cil.mncString
            } else {
                mcc = cil.mcc.toString()
                mnc = cil.mnc.toString()
            }

            return CellData(operatorName, LTE, cil.ci, mcc, mnc, cssg.dbm, cssg.asuLevel, cssg.level)
        }


        @RequiresApi(22)
        fun newInstance(cinl: CellInfoLte, subscriptionInfoList: MutableList<SubscriptionInfo>): CellData? {
            return if (subscriptionInfoList.size == 1)
                newInstance(cinl, subscriptionInfoList[0].carrierName.toString())
            else {
                val cil = cinl.cellIdentity
                val mcc: String
                val mnc: String
                if (Build.VERSION.SDK_INT == 28) {
                    mcc = cil.mccString
                    mnc = cil.mncString
                } else {
                    mcc = cil.mcc.toString()
                    mnc = cil.mnc.toString()
                }
                newInstance(cinl, getCarrierNameAndRemove(mnc, mcc, subscriptionInfoList))
            }
        }
    }
}
