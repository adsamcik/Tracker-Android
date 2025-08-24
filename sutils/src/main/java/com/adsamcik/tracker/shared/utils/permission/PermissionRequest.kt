package com.adsamcik.tracker.shared.utils.permission

import android.content.Context
import androidx.fragment.app.FragmentActivity

typealias PermissionResultCallback = (result: PermissionRequestResult) -> Unit
typealias RationaleCallback = (token: PermissionRequest.Token, permissionList: List<PermissionData>) -> Unit

/**
 * Permission request (temporary stub for Stage 0)
 */
@Suppress("unused")
class PermissionRequest private constructor(
    val context: Context,
    val permissionList: List<PermissionData>,
    val resultCallback: PermissionResultCallback,
    val rationaleCallback: RationaleCallback? = null
) {

    /**
     * Request token (temporary stub)
     */
    class Token {
        /**
         * Resumes permission request (stub)
         */
        fun continuePermissionRequest() {
            // TODO: Replace with Activity Result API
        }

        /**
         * Cancels permission request (stub)
         */
        fun cancelPermissionRequest() {
            // TODO: Replace with Activity Result API
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
         * Creates permission request with [FragmentActivity].
         */
        fun newInstance(context: FragmentActivity): Builder {
            return Builder(context)
        }

        /**
         * Creates permission request with [Context].
         */
        fun newInstance(context: Context): Builder {
            return Builder(context)
        }

        /**
         * Creates permission request with context (alias for newInstance)
         */
        fun with(context: Context): Builder {
            return Builder(context)
        }

        /**
         * Creates a builder from existing request
         */
        fun from(request: PermissionRequest): Builder {
            return Builder(request.context).apply {
                permissions(*request.permissionList.toTypedArray())
                request.rationaleCallback?.let { onRationale(it) }
            }
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
