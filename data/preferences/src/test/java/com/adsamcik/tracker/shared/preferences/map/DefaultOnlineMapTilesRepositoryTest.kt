package com.adsamcik.tracker.shared.preferences.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.preferences.store.LegacyPreferenceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultOnlineMapTilesRepositoryTest {

	private lateinit var context: Context

	@Before
	fun setup() {
		context = ApplicationProvider.getApplicationContext()
		val dsDir = context.filesDir.resolve("datastore")
		if (dsDir.exists()) {
			dsDir.listFiles()?.forEach { file -> file.delete() }
		}
		resetOnlineMapTilesForTests()
		LegacyPreferenceStore.resetForTests()
	}

	@Test
	fun default_state_is_disabled_with_openfreemap() = runTest {
		val repo = DefaultOnlineMapTilesRepository(context, Dispatchers.IO)
		val state = repo.data.first()
		assertFalse("online tiles must default OFF", state.enabled)
		assertEquals(DEFAULT_ONLINE_TILE_PROVIDER_ID, state.providerId)
		assertEquals("openfreemap", state.providerId)
		assertEquals("", state.customUrl)
	}

	@Test
	fun setEnabled_persists_and_reads_back() = runTest {
		val repo = DefaultOnlineMapTilesRepository(context, Dispatchers.IO)
		repo.data.first()
		repo.setEnabled(true)
		assertTrue(repo.data.first().enabled)
		repo.setEnabled(false)
		assertFalse(repo.data.first().enabled)
	}

	@Test
	fun setProviderId_persists() = runTest {
		val repo = DefaultOnlineMapTilesRepository(context, Dispatchers.IO)
		repo.data.first()
		repo.setProviderId("protomaps")
		assertEquals("protomaps", repo.data.first().providerId)
	}

	@Test
	fun setProviderId_empty_resolves_to_default() = runTest {
		val repo = DefaultOnlineMapTilesRepository(context, Dispatchers.IO)
		repo.data.first()
		// Explicitly persist empty -- domain layer should round-trip it back to default.
		repo.setProviderId("")
		assertEquals(DEFAULT_ONLINE_TILE_PROVIDER_ID, repo.data.first().providerId)
	}

	@Test
	fun setCustomUrl_persists() = runTest {
		val repo = DefaultOnlineMapTilesRepository(context, Dispatchers.IO)
		repo.data.first()
		val url = "https://example.com/style.json"
		repo.setCustomUrl(url)
		assertEquals(url, repo.data.first().customUrl)
	}

	@Test
	fun setCustomUrl_can_be_cleared() = runTest {
		val repo = DefaultOnlineMapTilesRepository(context, Dispatchers.IO)
		repo.data.first()
		repo.setCustomUrl("https://example.com/style.json")
		repo.setCustomUrl("")
		assertEquals("", repo.data.first().customUrl)
	}

	@Test
	fun state_default_constructor_matches_disk_default() {
		val expected = OnlineMapTilesState()
		assertFalse(expected.enabled)
		assertEquals("openfreemap", expected.providerId)
		assertEquals("", expected.customUrl)
	}
}
