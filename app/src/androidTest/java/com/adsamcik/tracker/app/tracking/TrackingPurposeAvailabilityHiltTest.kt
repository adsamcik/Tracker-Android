package com.adsamcik.tracker.app.tracking

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilityReader
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilityReporter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackingPurposeAvailabilityHiltTest {
	@Test
	fun readerAndReporterResolveToOneProcessSingleton() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val first = EntryPointAccessors.fromApplication(
			context,
			TrackingPurposeAvailabilityIdentityEntryPoint::class.java,
		)
		val second = EntryPointAccessors.fromApplication(
			context,
			TrackingPurposeAvailabilityIdentityEntryPoint::class.java,
		)

		assertSame(first.reader(), first.reporter())
		assertSame(first.reader(), second.reader())
		assertSame(first.reporter(), second.reporter())
	}
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TrackingPurposeAvailabilityIdentityEntryPoint {
	fun reader(): TrackingPurposeAvailabilityReader
	fun reporter(): TrackingPurposeAvailabilityReporter
}
