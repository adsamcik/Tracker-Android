package com.adsamcik.tracker.shared.utils.permission

import android.Manifest
import android.content.Context
import android.os.Build
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.extension.contains
import com.adsamcik.tracker.shared.utils.R
import com.adsamcik.tracker.shared.utils.extension.dynamicStyle
import com.afollestad.materialdialogs.MaterialDialog
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.multi.MultiplePermissionsListener

/**
 * Permission Manager, provides basic methods for managing permissions.
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
object PermissionManager {

    const val DIALOG_LAYER: Int = 1

    /**
     * Check if permission is granted and shows dialog if not.
     * If permissions is permanently denied, it will immediately return failure.
     */
    fun checkPermissions(
        permissionRequest: PermissionRequest
    ) {
        Dexter
            .withContext(permissionRequest.context)
            .withPermissions(permissionRequest.permissionList.map { it.name })
            .withListener(
                object : MultiplePermissionsListener {
                    override fun onPermissionsChecked(report: MultiplePermissionsReport) {
                        permissionRequest.resultCallback(
                            PermissionRequestResult(
                                report.grantedPermissionResponses.map { granted ->
                                    val data =
                                        requireNotNull(permissionRequest.permissionList.find { it.name == granted.permissionName })
                                    PermissionResult(
                                        data,
                                        isSuccess = true,
                                        isForeverDenied = false
                                    )
                                },
                                report.deniedPermissionResponses.map { denied ->
                                    val data =
                                        requireNotNull(permissionRequest.permissionList.find { it.name == denied.permissionName })
                                    PermissionResult(
                                        data,
                                        isSuccess = false,
                                        isForeverDenied = denied.isPermanentlyDenied
                                    )
                                }
                            )
                        )
                    }

                    override fun onPermissionRationaleShouldBeShown(
                        requests: MutableList<com.karumi.dexter.listener.PermissionRequest>,
                        token: PermissionToken
                    ) {
                        val rationalePermissions = permissionRequest
                            .permissionList
                            .filter { permissionRequest ->
                                requests.contains { request -> request.name == permissionRequest.name }
                            }

                        if (permissionRequest.rationaleCallback != null) {
                            permissionRequest.rationaleCallback.invoke(
                                PermissionRequest.Token(token),
                                rationalePermissions
                            )
                        } else {
                            defaultDialog(
                                permissionRequest.context,
                                PermissionRequest.Token(token),
                                rationalePermissions
                            ).show()
                        }
                    }
                }
            )
            .withErrorListener { Reporter.report("Dexter error ${it.name}") }
            .check()
    }

    private fun defaultDialog(
        context: Context,
        token: PermissionRequest.Token,
        rationalePermissions: Collection<PermissionData>
    ): MaterialDialog {
        return MaterialDialog(context).apply {
            message(text = rationalePermissions.joinToString { it.rationaleBuilder(context) })
            title(res = R.string.permission_rationale_title)
            positiveButton(res = R.string.permission_rationale_understood) {
                token.continuePermissionRequest()
            }
            negativeButton(res = R.string.permission_rationale_denied) {
                token.cancelPermissionRequest()
            }
            dynamicStyle(DIALOG_LAYER)
        }
    }

    /**
     * Check if permission is granted and shows dialog if not.
     * If permissions is permanently denied, it will immediately return failure.
     */
    fun checkPermissionsWithRationaleDialog(
        permissionRequest: PermissionRequest
    ) {
        val context = permissionRequest.context
        val builder = PermissionRequest.from(permissionRequest)
        builder.onRationale { token, permissionList ->
            defaultDialog(context, token, permissionList).show()
            permissionRequest.rationaleCallback?.invoke(token, permissionList)
        }
        checkPermissions(builder.build())
    }

    /**
     * Check if activity permission is granted and shows dialog if not.
     * If permissions is permanently denied, it will immediately return failure.
     *
     * @param context Context
     * @param callback Callback called when request is finished either successfully or unsuccessfully.
     */
    fun checkActivityPermissions(
        context: Context,
        callback: PermissionResultCallback
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("NAME_SHADOWING")
            (checkPermissionsWithRationaleDialog(
                PermissionRequest.with(context)
                    .permissions(listOf(
                        PermissionData(Manifest.permission.ACTIVITY_RECOGNITION) { context ->
                            context.getString(R.string.permission_rationale_activity)
                        }
                    ))
                    .onResult(callback)
                    .build()
            ))
        }
    }
}
