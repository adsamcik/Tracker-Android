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
			permissionRequest: PermissionRequest,
			dialog: DialogFactory,
			styleController: StyleController? = null,
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
													val data = requireNotNull(permissionRequest.permissionList.find { it.name == granted.permissionName })
													PermissionResult(
															data,
															isSuccess = true,
															isForeverDenied = false
													)
												},
												report.deniedPermissionResponses.map { denied ->
													val data = requireNotNull(permissionRequest.permissionList.find { it.name == denied.permissionName })
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
								val rationalePermissions = permissionRequest.permissionList
										.asSequence()
										.filter { permissionRequest ->
											requests.contains { request -> request.name == permissionRequest.name }
										}

								dialog(
										permissionRequest.context,
										token,
										rationalePermissions
								).apply {
									if (styleController != null) {
										dynamicStyle(styleController, DIALOG_LAYER)
									} else {
										dynamicStyle(DIALOG_LAYER)
									}
								}.show()
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
		return MaterialDialog(context).apply {
			message(text = rationalePermissions.joinToString { it.rationaleBuilder(context) })
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
			permissionRequest: PermissionRequest,
			styleController: StyleController? = null
	) {
		checkPermissions(permissionRequest, this::defaultDialog, styleController)
	}

	fun checkActivityPermissions(
			context: Context,
			styleController: StyleController? = null,
			callback: PermissionResultCallback
	) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			@Suppress("NAME_SHADOWING")
			checkPermissions(
					PermissionRequest.with(context)
							.permissions(listOf(
									PermissionData(Manifest.permission.ACTIVITY_RECOGNITION) { context ->
										context.getString(R.string.permission_rationale_activity)
									}
							))
							.onResult(callback)
							.build(),
					styleController
			)
		}
	}
}
