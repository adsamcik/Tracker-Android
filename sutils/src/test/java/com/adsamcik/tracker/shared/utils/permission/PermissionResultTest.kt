package com.adsamcik.tracker.shared.utils.permission

import android.content.pm.PackageManager
import com.adsamcik.tracker.shared.base.logging.ReporterFacade
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PermissionResult")
class PermissionResultTest {

	@Nested
	@DisplayName("PermissionResult data class")
	inner class PermissionResultData {

		@Test
		fun `stores data, success, and foreverDenied`() {
			val data = PermissionData("android.permission.CAMERA")
			val result = PermissionResult(data = data, isSuccess = true, isForeverDenied = false)

			result.data shouldBe data
			result.isSuccess shouldBe true
			result.isForeverDenied shouldBe false
		}

		@Test
		fun `denied result has isSuccess false`() {
			val data = PermissionData("android.permission.LOCATION")
			val result = PermissionResult(data = data, isSuccess = false, isForeverDenied = true)

			result.isSuccess shouldBe false
			result.isForeverDenied shouldBe true
		}

		@Test
		fun `equality works for identical results`() {
			val data = PermissionData("android.permission.CAMERA")
			val result1 = PermissionResult(data = data, isSuccess = true, isForeverDenied = false)
			val result2 = PermissionResult(data = data, isSuccess = true, isForeverDenied = false)

			result1 shouldBe result2
		}
	}

	@Nested
	@DisplayName("PermissionRequestResult")
	inner class PermissionRequestResultTests {

		@BeforeEach
		fun setUp() {
			mockkObject(ReporterFacade)
			every { ReporterFacade.report(any<String>()) } just Runs
			every { ReporterFacade.report(any<Throwable>()) } just Runs
		}

		@AfterEach
		fun tearDown() {
			unmockkObject(ReporterFacade)
		}

		@Test
		fun `isSuccess is true when no denied permissions`() {
			val cameraData = PermissionData("android.permission.CAMERA")
			val request = buildRequest(cameraData)

			val result = PermissionRequestResult.newFromResult(
				permissions = arrayOf("android.permission.CAMERA"),
				grantResults = intArrayOf(PackageManager.PERMISSION_GRANTED),
				request = request
			)

			result.isSuccess shouldBe true
		}

		@Test
		fun `isSuccess is false when any permission is denied`() {
			val cameraData = PermissionData("android.permission.CAMERA")
			val request = buildRequest(cameraData)

			val result = PermissionRequestResult.newFromResult(
				permissions = arrayOf("android.permission.CAMERA"),
				grantResults = intArrayOf(PackageManager.PERMISSION_DENIED),
				request = request
			)

			result.isSuccess shouldBe false
		}

		@Test
		fun `checkPermissionGranted returns true for granted permission`() {
			val cameraData = PermissionData("android.permission.CAMERA")
			val request = buildRequest(cameraData)

			val result = PermissionRequestResult.newFromResult(
				permissions = arrayOf("android.permission.CAMERA"),
				grantResults = intArrayOf(PackageManager.PERMISSION_GRANTED),
				request = request
			)

			result.checkPermissionGranted("android.permission.CAMERA") shouldBe true
		}

		@Test
		fun `checkPermissionGranted returns false for denied permission`() {
			val cameraData = PermissionData("android.permission.CAMERA")
			val request = buildRequest(cameraData)

			val result = PermissionRequestResult.newFromResult(
				permissions = arrayOf("android.permission.CAMERA"),
				grantResults = intArrayOf(PackageManager.PERMISSION_DENIED),
				request = request
			)

			result.checkPermissionGranted("android.permission.CAMERA") shouldBe false
		}

		@Test
		fun `checkPermissionGranted returns false for unknown permission`() {
			val cameraData = PermissionData("android.permission.CAMERA")
			val request = buildRequest(cameraData)

			val result = PermissionRequestResult.newFromResult(
				permissions = arrayOf("android.permission.CAMERA"),
				grantResults = intArrayOf(PackageManager.PERMISSION_GRANTED),
				request = request
			)

			result.checkPermissionGranted("android.permission.LOCATION") shouldBe false
		}

		@Test
		fun `handles multiple permissions with mixed results`() {
			val cameraData = PermissionData("android.permission.CAMERA")
			val locationData = PermissionData("android.permission.ACCESS_FINE_LOCATION")
			val request = buildRequest(cameraData, locationData)

			val result = PermissionRequestResult.newFromResult(
				permissions = arrayOf(
					"android.permission.CAMERA",
					"android.permission.ACCESS_FINE_LOCATION"
				),
				grantResults = intArrayOf(
					PackageManager.PERMISSION_GRANTED,
					PackageManager.PERMISSION_DENIED
				),
				request = request
			)

			result.isSuccess shouldBe false
			result.checkPermissionGranted("android.permission.CAMERA") shouldBe true
			result.checkPermissionGranted("android.permission.ACCESS_FINE_LOCATION") shouldBe false
		}

		@Test
		fun `all granted results in success`() {
			val cameraData = PermissionData("android.permission.CAMERA")
			val locationData = PermissionData("android.permission.ACCESS_FINE_LOCATION")
			val request = buildRequest(cameraData, locationData)

			val result = PermissionRequestResult.newFromResult(
				permissions = arrayOf(
					"android.permission.CAMERA",
					"android.permission.ACCESS_FINE_LOCATION"
				),
				grantResults = intArrayOf(
					PackageManager.PERMISSION_GRANTED,
					PackageManager.PERMISSION_GRANTED
				),
				request = request
			)

			result.isSuccess shouldBe true
		}

		@Test
		fun `reports unknown grant result codes`() {
			val cameraData = PermissionData("android.permission.CAMERA")
			val request = buildRequest(cameraData)

			PermissionRequestResult.newFromResult(
				permissions = arrayOf("android.permission.CAMERA"),
				grantResults = intArrayOf(999),
				request = request
			)

			verify { ReporterFacade.report(match<String> { it.contains("999") }) }
		}

		@Test
		fun `empty permissions array produces successful result`() {
			val request = buildRequest()

			val result = PermissionRequestResult.newFromResult(
				permissions = emptyArray(),
				grantResults = intArrayOf(),
				request = request
			)

			result.isSuccess shouldBe true
		}

		private fun buildRequest(vararg permissions: PermissionData): PermissionRequest {
			val context = io.mockk.mockk<android.content.Context>()
			val builder = PermissionRequest.newInstance(context)
				.permissions(permissions.toList())
				.onResult { }
			return builder.build()
		}
	}
}
