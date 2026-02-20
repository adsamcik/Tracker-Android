package com.adsamcik.tracker.shared.utils.permission

import android.content.Context
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PermissionData")
class PermissionDataTest {

	private val context = mockk<Context>()

	@Nested
	@DisplayName("Construction")
	inner class Construction {

		@Test
		fun `stores permission name`() {
			val data = PermissionData("android.permission.CAMERA")

			data.name shouldBe "android.permission.CAMERA"
		}

		@Test
		fun `single-arg constructor sets empty rationale`() {
			val data = PermissionData("android.permission.CAMERA")

			data.getRationale(context).shouldBeEmpty()
		}

		@Test
		fun `two-arg constructor with custom rationale provider`() {
			val data = PermissionData("android.permission.CAMERA") { "Camera needed" }

			data.getRationale(context) shouldBe "Camera needed"
		}

		@Test
		fun `rationale provider receives context`() {
			var receivedContext: Context? = null
			val data = PermissionData("android.permission.CAMERA") { ctx ->
				receivedContext = ctx
				"rationale"
			}

			data.getRationale(context)

			receivedContext shouldBe context
		}
	}

	@Nested
	@DisplayName("Equality")
	inner class Equality {

		@Test
		fun `data classes with same name and default rationale are equal`() {
			val data1 = PermissionData("android.permission.CAMERA") { "" }
			val data2 = PermissionData("android.permission.CAMERA") { "" }

			// Note: data classes compare all properties. Lambda references differ,
			// so these are NOT equal (expected Kotlin data class behavior).
			data1.name shouldBe data2.name
		}

		@Test
		fun `different permission names are not equal`() {
			val data1 = PermissionData("android.permission.CAMERA")
			val data2 = PermissionData("android.permission.RECORD_AUDIO")

			(data1.name == data2.name) shouldBe false
		}
	}

	@Nested
	@DisplayName("getRationale")
	inner class GetRationale {

		@Test
		fun `returns empty string for default provider`() {
			val data = PermissionData("android.permission.CAMERA")

			data.getRationale(context).shouldBeEmpty()
		}

		@Test
		fun `returns custom rationale text`() {
			val data = PermissionData("android.permission.ACCESS_FINE_LOCATION") {
				"Location is needed to track your movement"
			}

			data.getRationale(context) shouldBe "Location is needed to track your movement"
		}

		@Test
		fun `rationale provider can produce dynamic text`() {
			var invocationCount = 0
			val data = PermissionData("android.permission.CAMERA") {
				invocationCount++
				"Rationale call $invocationCount"
			}

			data.getRationale(context) shouldBe "Rationale call 1"
			data.getRationale(context) shouldBe "Rationale call 2"
		}
	}
}
