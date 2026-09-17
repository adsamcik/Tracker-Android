package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedPreferencesSourceCallerAcceptedAuthorityRepositoryTest {
	@Test
	fun `accepted authority survives repository recreation without overwrite`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Context>()
		context.getSharedPreferences("source_caller_authority", Context.MODE_PRIVATE)
			.edit()
			.clear()
			.commit() shouldBe true
		val dispatcher = TestDispatchersProvider(StandardTestDispatcher(testScheduler))
		val reference = SourceCallerReplayReference("accepted-reference")
		val first = SharedPreferencesSourceCallerAcceptedAuthorityRepository(context, dispatcher)

		first.storeIfAbsent(reference, "encoded-authority") shouldBe true
		val recreated =
			SharedPreferencesSourceCallerAcceptedAuthorityRepository(context, dispatcher)
		recreated.load(reference) shouldBe "encoded-authority"
		recreated.storeIfAbsent(reference, "replacement") shouldBe false
		recreated.load(reference) shouldBe "encoded-authority"
	}
}
