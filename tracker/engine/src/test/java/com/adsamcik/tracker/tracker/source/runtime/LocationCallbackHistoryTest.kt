package com.adsamcik.tracker.tracker.source.runtime

import android.location.Location
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationCallbackHistoryTest {
	@Test
	fun `unchanged refresh keeps valid fixes collected before the policy tick`() {
		val original = context(1, 10)
		val refreshed = refreshLocationCallbackContexts(listOf(original), original.copy(
			minimumObservedElapsedRealtimeNanos = 80), 80)
		val batch = batch(refreshed, 20, 70, 90)
		assertEquals(listOf(20L, 70L, 90L), partitionLocationContexts(batch).groups.values.flatten()
			.map(Location::getElapsedRealtimeNanos))
		assertEquals(10L, refreshed.single().minimumObservedElapsedRealtimeNanos)
	}

	@Test
	fun `late batch crossing a session join retains each exact authorization`() {
		val ambient = context(1, 10)
		val joined = context(2, 60)
		val history = refreshLocationCallbackContexts(listOf(ambient), joined, 80)
		val partition = partitionLocationContexts(batch(history, 30, 59, 60, 90))
		assertEquals(0, partition.unattributedCount)
		assertEquals(listOf(30L, 59L), partition.groups.entries.single {
			it.key.attribution.authorizationRevision == 1L }.value.map(Location::getElapsedRealtimeNanos))
		assertEquals(listOf(60L, 90L), partition.groups.entries.single {
			it.key.attribution.authorizationRevision == 2L }.value.map(Location::getElapsedRealtimeNanos))
	}

	@Test
	fun `queued callback retains its immutable context when a later purpose joins`() {
		val original = context(1, 10)
		val queued = batch(listOf(original), 30)
		refreshLocationCallbackContexts(listOf(original), context(2, 60), 80)
		assertEquals(1L, partitionLocationContexts(queued).groups.keys.single().attribution.authorizationRevision)
	}

	@Test
	fun `bounded history cannot lend current consent to an evicted observation`() {
		var history = listOf(context(1, 1))
		for (revision in 2L..70L) history = refreshLocationCallbackContexts(history,
			context(revision, revision), revision)
		assertTrue(history.size <= 64)
		val partition = partitionLocationContexts(batch(history, 3, 69, 70).copy(collectionBoundary = 1))
		assertEquals(1, partition.unattributedCount)
		assertEquals(listOf(69L, 70L), partition.groups.values.flatten().map(Location::getElapsedRealtimeNanos))
	}

	@Test
	fun `pre-registration backlog is excluded without a false admission gap`() {
		val partition = partitionLocationContexts(batch(listOf(context(1, 30)), 10, 20, 50))
		assertEquals(0, partition.unattributedCount)
		assertEquals(listOf(50L), partition.groups.values.flatten().map(Location::getElapsedRealtimeNanos))
	}

	@Test(expected = IllegalArgumentException::class)
	fun `history cannot cross a physical generation`() {
		val old = context(1, 10)
		refreshLocationCallbackContexts(listOf(old), context(2, 30).let {
			it.copy(attribution = it.attribution.copy(registrationGeneration = 2)) }, 40)
	}

	private fun context(revision: Long, since: Long) = LocationCallbackContext(
		LocationDeliveryAttribution(SourceInstanceId("location"), 1, "physical", revision,
			if (revision == 1L) 2 else 3, "authority-$revision", revision, "boot", 1),
		false, mockk(relaxed = true), since, maximumEvidenceAgeNanos = 120,
	)

	private fun batch(contexts: List<LocationCallbackContext>, vararg observed: Long) = RawLocationBatch(
		observed.map { time -> Location("gps").apply {
			latitude = 50.0; longitude = 14.0; accuracy = 5f; elapsedRealtimeNanos = time
		} }, 100, 100, 1, contexts.last(), contexts, contexts.first().minimumObservedElapsedRealtimeNanos,
	)
}
