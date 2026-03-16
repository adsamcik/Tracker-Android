package com.adsamcik.tracker.shared.utils.permission

import android.content.Context
import androidx.activity.ComponentActivity

typealias PermissionResultCallback = (result: PermissionRequestResult) -> Unit
typealias RationaleCallback = (token: PermissionRequest.Token, permissionList: List<PermissionData>) -> Unit

/**
 * Permission request data class for Activity Result API-based permission requests.
 * 
 * Contract: Encapsulates permission request with callbacks for result and rationale handling.
 * Inputs: Context, permission list, result callback, optional rationale callback.
 * Outputs: Triggers callbacks when permissions are granted/denied or rationale needed.
 */
@Suppress("unused")
class PermissionRequest private constructor(
    val context: Context,
    val permissionList: List<PermissionData>,
    val resultCallback: PermissionResultCallback,
    val rationaleCallback: RationaleCallback? = null
) {

    /**
     * Token provided to rationale callback for continuing or cancelling the permission request.
     */
    class Token internal constructor(
        private val onContinue: () -> Unit,
        private val onCancel: () -> Unit
    ) {
        /**
         * Resumes permission request after showing rationale.
         */
        fun continuePermissionRequest() {
            onContinue()
        }

        /**
         * Cancels permission request (user declined after seeing rationale).
         */
        fun cancelPermissionRequest() {
            onCancel()
        }
    }

    /**
     * Builder for permission request
     */
    class Builder(private val context: Context) {
        private val permissionList = mutableListOf<PermissionData>()
        private var resultCallback: PermissionResultCallback? = null
        private var rationaleCallback: RationaleCallback? = null

        fun permission(permissionData: PermissionData): Builder {
            permissionList.add(permissionData)
            return this
        }

        fun permissions(vararg permissionData: PermissionData): Builder {
            permissionList.addAll(permissionData)
            return this
        }

        fun permissions(permissionDataList: List<PermissionData>): Builder {
            permissionList.addAll(permissionDataList)
            return this
        }

        fun onResult(callback: PermissionResultCallback): Builder {
            resultCallback = callback
            return this
        }

        fun onRationale(callback: RationaleCallback): Builder {
            rationaleCallback = callback
            return this
        }

        fun build(): PermissionRequest {
            return PermissionRequest(
                context = context,
                permissionList = permissionList,
                resultCallback = requireNotNull(resultCallback),
                rationaleCallback = rationaleCallback
            )
        }
    }

    companion object {
        /**
         * Creates permission request with [ComponentActivity].
         */
        fun newInstance(context: ComponentActivity): Builder {
            return Builder(context)
        }

        /**
         * Creates permission request with [Context].
         */
        fun newInstance(context: Context): Builder {
            return Builder(context)
        }

        /**
         * Creates permission request with legacy callback
         */
        fun newInstance(
            context: Context,
            permissionData: PermissionData,
            callback: PermissionResultCallback
        ): PermissionRequest {
            return Builder(context)
                .permission(permissionData)
                .onResult(callback)
                .build()
        }

        /**
         * Creates permission request with legacy callback
         */
        fun newInstance(
            context: Context,
            permissionList: List<PermissionData>,
            callback: PermissionResultCallback
        ): PermissionRequest {
            return Builder(context)
                .permissions(*permissionList.toTypedArray())
                .onResult(callback)
                .build()
        }
    }
}
