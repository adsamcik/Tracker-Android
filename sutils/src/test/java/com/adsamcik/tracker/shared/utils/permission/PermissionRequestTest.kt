package com.adsamcik.tracker.shared.utils.permission

import android.content.Context
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("PermissionRequest")
class PermissionRequestTest {

	private val context = mockk<Context>()

	@Nested
	@DisplayName("Builder")
	inner class BuilderTests {

		@Test
		fun `builds request with single permission`() {
			val permissionData = PermissionData("android.permission.CAMERA")

			val request = PermissionRequest.newInstance(context)
				.permission(permissionData)
				.onResult { }
				.build()

			request.permissionList shouldHaveSize 1
			request.permissionList[0].name shouldBe "android.permission.CAMERA"
		}

		@Test
		fun `builds request with multiple permissions via vararg`() {
			val camera = PermissionData("android.permission.CAMERA")
			val location = PermissionData("android.permission.ACCESS_FINE_LOCATION")

			val request = PermissionRequest.newInstance(context)
				.permissions(camera, location)
				.onResult { }
				.build()

			request.permissionList shouldHaveSize 2
		}

		@Test
		fun `builds request with permissions list`() {
			val permissions = listOf(
				PermissionData("android.permission.CAMERA"),
				PermissionData("android.permission.RECORD_AUDIO")
			)

			val request = PermissionRequest.newInstance(context)
				.permissions(permissions)
				.onResult { }
				.build()

			request.permissionList shouldHaveSize 2
		}

		@Test
		fun `throws when result callback is not set`() {
			assertThrows<IllegalArgumentException> {
				PermissionRequest.newInstance(context)
					.permission(PermissionData("android.permission.CAMERA"))
					.build()
			}
		}

		@Test
		fun `rationaleCallback is null when not set`() {
			val request = PermissionRequest.newInstance(context)
				.permission(PermissionData("android.permission.CAMERA"))
				.onResult { }
				.build()

			request.rationaleCallback.shouldBeNull()
		}

		@Test
		fun `rationaleCallback is set when provided`() {
			val request = PermissionRequest.newInstance(context)
				.permission(PermissionData("android.permission.CAMERA"))
				.onResult { }
				.onRationale { _, _ -> }
				.build()

			request.rationaleCallback.shouldNotBeNull()
		}

		@Test
		fun `preserves context reference`() {
			val request = PermissionRequest.newInstance(context)
				.permission(PermissionData("android.permission.CAMERA"))
				.onResult { }
				.build()

			request.context shouldBe context
		}

		@Test
		fun `chaining adds all permissions`() {
			val request = PermissionRequest.newInstance(context)
				.permission(PermissionData("android.permission.CAMERA"))
				.permission(PermissionData("android.permission.RECORD_AUDIO"))
				.permission(PermissionData("android.permission.ACCESS_FINE_LOCATION"))
				.onResult { }
				.build()

			request.permissionList shouldHaveSize 3
		}
	}

	@Nested
	@DisplayName("Factory methods")
	inner class FactoryMethods {

		@Test
		fun `newInstance with single permission and callback`() {
			val permissionData = PermissionData("android.permission.CAMERA")
			var callbackInvoked = false

			val request = PermissionRequest.newInstance(
				context,
				permissionData
			) { callbackInvoked = true }

			request.permissionList shouldHaveSize 1
			request.permissionList[0].name shouldBe "android.permission.CAMERA"
			request.resultCallback(
				PermissionRequestResult.newFromResult(
					arrayOf("android.permission.CAMERA"),
					intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED),
					request
				)
			)
			callbackInvoked shouldBe true
		}

		@Test
		fun `newInstance with permission list and callback`() {
			val permissions = listOf(
				PermissionData("android.permission.CAMERA"),
				PermissionData("android.permission.RECORD_AUDIO")
			)

			val request = PermissionRequest.newInstance(context, permissions) { }

			request.permissionList shouldHaveSize 2
		}

		@Test
		fun `newInstance creates builder`() {
			val request = PermissionRequest.newInstance(context)
				.permission(PermissionData("android.permission.CAMERA"))
				.onResult { }
				.build()

			request.permissionList shouldHaveSize 1
		}

		@Test
		fun `builder can copy permissions from existing request`() {
			val original = PermissionRequest.newInstance(context)
				.permission(PermissionData("android.permission.CAMERA"))
				.permission(PermissionData("android.permission.RECORD_AUDIO"))
				.onResult { }
				.build()

			val copy = PermissionRequest.Builder(original.context)
				.permissions(original.permissionList)
				.onResult { }
				.build()

			copy.permissionList shouldHaveSize 2
			copy.permissionList.map { it.name } shouldContainExactly listOf(
				"android.permission.CAMERA",
				"android.permission.RECORD_AUDIO"
			)
		}

		@Test
		fun `builder can preserve rationale callback from existing request`() {
			val original = PermissionRequest.newInstance(context)
				.permission(PermissionData("android.permission.CAMERA"))
				.onResult { }
				.onRationale { _, _ -> }
				.build()

			val copy = PermissionRequest.Builder(original.context)
				.permissions(original.permissionList)
				.onRationale(requireNotNull(original.rationaleCallback))
				.onResult { }
				.build()

			copy.rationaleCallback.shouldNotBeNull()
		}
	}

	@Nested
	@DisplayName("Token")
	inner class TokenTests {

		@Test
		fun `continuePermissionRequest invokes onContinue`() {
			var continued = false
			val token = PermissionRequest.Token(
				onContinue = { continued = true },
				onCancel = { }
			)

			token.continuePermissionRequest()

			continued shouldBe true
		}

		@Test
		fun `cancelPermissionRequest invokes onCancel`() {
			var cancelled = false
			val token = PermissionRequest.Token(
				onContinue = { },
				onCancel = { cancelled = true }
			)

			token.cancelPermissionRequest()

			cancelled shouldBe true
		}
	}
}
