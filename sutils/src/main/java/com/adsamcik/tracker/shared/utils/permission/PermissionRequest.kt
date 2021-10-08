package com.adsamcik.tracker.shared.utils.permission

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.karumi.dexter.PermissionToken

typealias PermissionResultCallback = (result: PermissionRequestResult) -> Unit
typealias RationaleCallback = (token: PermissionRequest.Token, permissionList: List<PermissionData>) -> Unit

/**
 * Permission request
 */
@Suppress("unused")
class PermissionRequest private constructor(
    val context: Context,
    val permissionList: List<PermissionData>,
    val resultCallback: PermissionResultCallback,
    val rationaleCallback: RationaleCallback? = null
) {

    /**
     * Permission request builder
     */
    class Builder(val context: Context) {

        private val permissions = mutableListOf<PermissionData>()

        private var onPermissionRationale: RationaleCallback? = null

        private var onPermissionResult: PermissionResultCallback? = null

        /**
         * Sets required permissions
         */
        fun permissions(permissions: Collection<PermissionData>): Builder {
            this.permissions.addAll(permissions)
            return this
        }

        /**
         * Sets required permissions
         */
        fun permissions(vararg permissions: Pair<String, (context: Context) -> String>): Builder {
            permissions(permissions.map { PermissionData(it.first, it.second) })
            return this
        }

        /**
         * Sets callback to create rationale
         */
        fun onRationale(callback: RationaleCallback): Builder {
            onPermissionRationale = callback
            return this
        }

        /**
         * Sets on permission result callback
         */
        fun onResult(callback: PermissionResultCallback): Builder {
            onPermissionResult = callback
            return this
        }


        /**
         * Builds request
         */
        fun build(): PermissionRequest {
            val permissionCallback =
                requireNotNull(onPermissionResult) { "Permission result callback needs to be set" }
            return PermissionRequest(
                context,
                permissions,
                permissionCallback,
                onPermissionRationale
            )
        }
    }

    /**
     * Request token
     */
    class Token(private val token: PermissionToken) {
        /**
         * Resumes permission request
         */
        fun continuePermissionRequest() {
            token.continuePermissionRequest()
        }

        /**
         * Cancels permission request
         */
        fun cancelPermissionRequest() {
            token.cancelPermissionRequest()
        }

    }

    companion object {
        /**
         * Creates permission request with [FragmentActivity].
         */
        fun with(activity: FragmentActivity): Builder = Builder(activity)

        /**
         * Creates permission request with [Context].
         */
        fun with(context: Context): Builder = Builder(context)

        /**
         * Creates builder from active request
         */
        fun from(request: PermissionRequest): Builder = Builder(request.context).apply {
            permissions(request.permissionList)
            request.rationaleCallback?.let { onRationale(it) }
            onResult(request.resultCallback)
        }
    }
}

/**
 * Permission data
 *
 * @param name Permission name (from manifest)
 * @param rationaleBuilder Rationale text builder
 */
data class PermissionData(val name: String, val rationaleBuilder: (context: Context) -> String)
