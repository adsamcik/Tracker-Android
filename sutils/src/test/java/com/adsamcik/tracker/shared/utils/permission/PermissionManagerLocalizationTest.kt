package com.adsamcik.tracker.shared.utils.permission

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.utils.R
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import kotlin.test.assertTrue

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class PermissionManagerLocalizationTest {

	private val context: Application
		get() = ApplicationProvider.getApplicationContext()

	@Test
	fun `permission rationale title resolves to non-empty default text`() {
		assertTrue(context.getString(R.string.permission_rationale_title).isNotBlank())
	}

	@Test
	fun `permission rationale dialog intro says data stays on this device`() {
		val text = context.getString(R.string.permission_rationale_dialog_intro)

		assertTrue(text.contains("stays on this device"))
	}

	@Test
	fun `activity permission rationale says data stays on this device`() {
		val text = context.getString(R.string.permission_rationale_activity)

		assertTrue(text.contains("on this device"))
	}

	@Test
	fun `location permission rationale says data stays on this device`() {
		val text = context.getString(R.string.permission_rationale_location)

		assertTrue(text.contains("on this device"))
	}

	@Test
	fun `phone state permission rationale says data stays on this device`() {
		val text = context.getString(R.string.permission_rationale_phone_state)

		assertTrue(text.contains("this device"))
	}

	@Test
	fun `background location permission rationale mentions in the background`() {
		val text = context.getString(R.string.permission_rationale_background_location)

		assertTrue(text.contains("in the background"))
	}
}
