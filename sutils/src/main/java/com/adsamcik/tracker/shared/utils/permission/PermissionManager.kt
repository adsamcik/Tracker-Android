package com.adsamcik.tracker.shared.utils.permission

import android.Manifest
import android.content.Context
import android.os.Build
import com.adsamcik.tracker.shared.base.extension.contains
import com.adsamcik.tracker.shared.utils.R
import com.adsamcik.tracker.shared.utils.debug.Reporter
import com.adsamcik.tracker.shared.utils.extension.dynamicStyle
import com.adsamcik.tracker.shared.utils.style.StyleController
import com.afollestad.materialdialogs.MaterialDialog
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.multi.MultiplePermissionsListener

/**
 * Dialog Factory type alias
 */
typealias DialogFactory = (
		context: Context,
		token: PermissionToken,
		rationalePermissions: Sequence<PermissionData>
) -> MaterialDialog

/**
 * Permission Manager, provides basic methods for managing permissions.
 */
object PermissionManager {

	const val DIALOG_LAYER = 1

	/**
	 * Check if permission is granted and shows dialog if not.
	 * If permissions is permanently denied, it will immediately return failure.
	 */
	fun checkPermissions(
			context: Context,
			permissionRequest: PermissionRequest,
			dialog: DialogFactory,
			styleController: StyleController? = null,
	) {
		Dexter
				.withContext(context)
				.withPermissions(permissionRequest.permissionList.map { it.name })
				.withListener(
						object : MultiplePermissionsListener {
							override fun onPermissionsChecked(report: MultiplePermissionsReport) {
								permissionRequest.callback(
										PermissionResult(
												report.grantedPermissionResponses.map { it.permissionName },
												report.deniedPermissionResponses.map { it.permissionName })
								)
							}

							override fun onPermissionRationaleShouldBeShown(
									requests: MutableList<com.karumi.dexter.listener.PermissionRequest>,
									token: PermissionToken
							) {
								val rationalePermissions = permissionRequest.permissionList
										.asSequence()
										.filter { permissionRequest ->
											requests.contains { request -> request.name == permissionRequest.name }
										}

								dialog(context, token, rationalePermissions).apply {
									if (styleController != null) {
										dynamicStyle(styleController, DIALOG_LAYER)
									} else {
										dynamicStyle(DIALOG_LAYER)
									}
								}
							}
						}
				)
				.withErrorListener { Reporter.report("Dexter error ${it.name}") }
				.check()
	}

	private fun defaultDialog(
			context: Context,
			token: PermissionToken,
			rationalePermissions: Sequence<PermissionData>
	): MaterialDialog {
		val resources = context.resources
		return MaterialDialog(context).apply {
			message(text = rationalePermissions.joinToString { resources.getString(it.rationale) })
			title(res = R.string.permission_rationale_title)
			positiveButton(res = R.string.permission_rationale_understood) {
				token.continuePermissionRequest()
			}
			negativeButton(res = R.string.permission_rationale_denied) {
				token.cancelPermissionRequest()
			}
		}
	}

	fun checkPermissions(
			context: Context,
			permissionRequest: PermissionRequest,
			styleController: StyleController? = null
	) {
		checkPermissions(context, permissionRequest, this::defaultDialog, styleController)
	}

	fun checkActivityPermissions(
			context: Context,
			styleController: StyleController? = null,
			callback: PermissionCallback
	) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			checkPermissions(
					context,
					PermissionRequest(
							arrayOf(
									PermissionData(
											Manifest.permission.ACTIVITY_RECOGNITION,
											R.string.permission_rationale_activity
									)
							),
							callback
					),
					styleController
			)
		}
	}
}
