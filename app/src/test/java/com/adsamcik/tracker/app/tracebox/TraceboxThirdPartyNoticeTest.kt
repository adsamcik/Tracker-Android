package com.adsamcik.tracker.app.tracebox

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.license.TraceboxThirdPartyNotice
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TraceboxThirdPartyNoticeTest {
	@Test
	fun `loads every embedded Tracebox component notice from the dependency AAR`() {
		val context = ApplicationProvider.getApplicationContext<Application>()
		val license = TraceboxThirdPartyNotice.load(context.resources)
		val text = checkNotNull(license.notice.license)
			.readFullTextFromResources(context)

		license.name shouldBe TraceboxThirdPartyNotice.DISPLAY_NAME
		listOf(
			"crashpad",
			"mini_chromium",
			"linux_syscall_support",
			"zlib",
			"googletest",
			"chromium_buildtools",
		).forEach { section ->
			text shouldContain "===== BEGIN $section ====="
			text shouldContain "===== END $section ====="
		}
	}
}
